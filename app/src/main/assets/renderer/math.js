// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 DigiGate
// Local Android extension: parse at the token boundary, never rewrite Markdown source or HTML.
// Bare TeX is intentionally conservative: a known command is required; ordinary variables,
// English prose, URLs, code and arbitrary backslash commands are not inferred as mathematics.
// A hard per-candidate budget bounds malformed/unclosed input as well as valid expressions.
const MAX_BARE_LENGTH = 2048;
const COMMANDS = new Set(('implies dots ldots cdots alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota kappa lambda mu nu xi pi varpi rho varrho sigma varsigma tau upsilon phi varphi chi psi omega Gamma Delta Theta Lambda Xi Pi Sigma Upsilon Phi Psi Omega sin cos tan cot sec csc arcsin arccos arctan sinh cosh tanh log ln exp lim min max sup inf frac dfrac tfrac sqrt left right infty cdot times div pm mp le leq ge geq ne neq approx equiv sim in notin subset supset subseteq supseteq cup cap emptyset varnothing forall exists neg land lor to mapsto rightarrow leftarrow Rightarrow Leftrightarrow sum prod int iint oint partial nabla vec overline underline hat bar dot ddot mathbb mathcal mathrm mathbf mathit text operatorname limits nolimits').split(' '));
export function hasTexSyntax(text) {
    return /\\(?:[([]|%|[A-Za-z]+)/.test(text);
}
function escaped(src, pos) {
    let n = 0;
    while (pos > 0 && src[--pos] === '\\') n++;
    return n % 2 === 1;
}
function push(state, silent, content, end, display = false) {
    if (!silent) {
        const token = state.push(display ? 'math_block' : 'math_inline', 'math', 0);
        token.content = content;
        token.block = display;
        token.markup = display ? '\\[' : '\\(';
    }
    state.pos = end;
    return true;
}
function explicitTex(state, silent) {
    const start = state.pos;
    const opener = state.src.slice(start, start + 2);
    if (opener !== '\\(' && opener !== '\\[') return false;
    const close = opener === '\\(' ? '\\)' : '\\]';
    let end = start + 2;
    while ((end = state.src.indexOf(close, end)) !== -1) {
        if (!escaped(state.src, end)) {
            const content = state.src.slice(start + 2, end);
            if (!content.trim()) return false;
            return push(state, silent, content, end + 2, opener === '\\[');
        }
        end += 2;
    }
    return false;
}
function bareTex(state, silent) {
    if (state.linkLevel > 0) return false;
    const src = state.src;
    const start = state.pos;
    // No starts in a word, URL/path, escaped backslash, or a numbered list marker.
    if (start && /[\w\\/:]/.test(src[start - 1])) return false;
    if (!/[A-Za-z0-9(\\]/.test(src[start] || '')) return false;
    const slash = src.indexOf('\\', start);
    if (slash < start || slash - start >= MAX_BARE_LENGTH) return false;
    let pos = start, braces = 0, parens = 0, command = false;
    const scanEnd = Math.min(state.posMax, start + MAX_BARE_LENGTH);
    while (pos < scanEnd) {
        const ch = src[pos];
        if (ch === '\\') {
            if (src[pos + 1] === '{') { parens++; pos += 2; continue; }
            if (src[pos + 1] === '}') { if (!parens) break; parens--; pos += 2; continue; }
            if (/[%!,;: ]/.test(src[pos + 1] || '')) {
                if (src[pos + 1] === '%') command = true;
                pos += 2; continue;
            }
            const match = /^\\([A-Za-z]+)/.exec(src.slice(pos, scanEnd));
            if (!match || !COMMANDS.has(match[1])) break;
            command = true;
            pos += match[0].length;
        } else if (/[A-Za-z]/.test(ch)) {
            const word = /^[A-Za-z]+/.exec(src.slice(pos))[0];
            if (word.length !== 1) break;
            pos += word.length;
        } else if (/[0-9]/.test(ch)) {
            pos += /^\d+(?:\.\d+)?/.exec(src.slice(pos))[0].length;
        } else if (ch === '{') { braces++; pos++; }
        else if (ch === '}') { if (!braces) break; braces--; pos++; }
        else if (ch === '(' || ch === '[') { parens++; pos++; }
        else if (ch === ')' || ch === ']') { if (!parens) break; parens--; pos++; }
        else if (ch === ',' && parens) pos++;
        else if (/[ \t=+\-*/^_!>'|]/.test(ch)) pos++;
        else if (ch === '<' && !/[A-Za-z/!]/.test(src[pos + 1] || '')) pos++;
        else break;
    }
    if (!command || braces || parens || pos === scanEnd && scanEnd < state.posMax) return false;
    let content = src.slice(start, pos).trimEnd();
    // Do not swallow Markdown emphasis, table pipes or an unfinished arithmetic operator.
    content = content.replace(/[ \t=+\-*/^_|<>]+$/, '');
    if (!content || !/\\(?:[A-Za-z]+|%)/.test(content)) return false;
    return push(state, silent, content, start + content.length);
}
// Replace the vendor's single-dollar rule: whitespace and a digit after the closer indicate
// ordinary prices, not a formula. Double-dollar blocks remain the vendor's responsibility.
function dollars(state, silent) {
    const src = state.src, start = state.pos;
    if (src[start] !== '$' || src[start + 1] === '$') return false;
    if (/\s/.test(src[start + 1] || ' ') || escaped(src, start)) return false;
    let end = start + 1;
    while ((end = src.indexOf('$', end)) !== -1) {
        if (src.slice(start, end).includes('\n')) return false;
        if (!escaped(src, end) && src[end + 1] !== '$' && !/\s/.test(src[end - 1]) && !/\d/.test(src[end + 1] || '')) {
            return push(state, silent, src.slice(start + 1, end), end + 1);
        }
        // Never search across a double-dollar boundary or line break.
        if (src[end + 1] === '$') return false;
        end++;
    }
    return false;
}
export function extendedMathPlugin(md) {
    // markdown-it's text rule greedily consumes Chinese prose and the ASCII prefix of a
    // formula before reaching its first backslash. Split that text token, not the source.
    const textRule = md.inline.ruler.__rules__.find(rule => rule.name === 'text').fn;
    md.inline.ruler.at('text', (state, silent) => {
        const start = state.pos, pending = state.pending;
        if (!textRule(state, silent)) return false;
        const end = state.pos, nextPending = state.pending;
        // text normally stops at the first backslash. No command ahead: leave its fast
        // path intact. Only the bounded tail can belong to that command's expression.
        const slash = state.src.indexOf('\\', start);
        if (slash === -1 || slash > end + MAX_BARE_LENGTH) return true;
        for (let pos = Math.max(start + 1, slash - MAX_BARE_LENGTH + 1); pos < end; pos++) {
            if (!/[A-Za-z0-9(]/.test(state.src[pos])) continue;
            state.pos = pos;
            if (bareTex(state, true)) {
                state.pos = pos;
                state.pending = pending + state.src.slice(start, pos);
                return true;
            }
        }
        state.pos = end;
        state.pending = nextPending;
        return true;
    });
    md.inline.ruler.before('escape', 'tex-delimiters', explicitTex);
    md.inline.ruler.before('text', 'bare-tex', bareTex);
    md.inline.ruler.at('math_inline', dollars);
    return md;
}

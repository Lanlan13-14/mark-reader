// SPDX-License-Identifier: GPL-3.0-only
// Shared Node/browser suite: no npm installation or build required.
import MarkdownIt from '../../app/src/main/assets/renderer/vendor/markdown-it.js';
import { loadMathPlugin, getPlugins } from '../../app/src/main/assets/renderer/plugins.js';
import { detectDocumentNeeds } from '../../app/src/main/assets/renderer/lazy.js';
export async function runMathTests(assert, fixture, original = '') {
    let count = 0;
    const check = (condition, name) => { assert(condition, name); count++; };
    await loadMathPlugin();
    const md = MarkdownIt({ html: true, linkify: true });
    getPlugins().forEach(plugin => md.use(plugin));
    const math = source => md.render(source);
    const annotations = source => [...math(source).matchAll(/<annotation encoding="application\/x-tex">([\s\S]*?)<\/annotation>/g)].map(m => m[1]);
    const cases = [
        [String.raw`函数 f(x) = \ln x - \left(\frac{1}{e}\right)^x = \ln x - e^{-x} 的定义域为 (0, +\infty)。`, [String.raw`f(x) = \ln x - \left(\frac{1}{e}\right)^x = \ln x - e^{-x}`, String.raw`(0, +\infty)`]],
        [String.raw`\alpha`, [String.raw`\alpha`]],
        [String.raw`\sin\alpha < 0`, [String.raw`\sin\alpha &lt; 0`]],
        ['$x^2$', ['x^2']], ['$$x^2$$', ['x^2\n']],
        [String.raw`\( x^2 \)`, [' x^2 ']],
        [String.raw`\[ x^2 \]`, [' x^2 ']],
        [String.raw`中文\alpha，随后 \sin\alpha < 0。`, [String.raw`\alpha`, String.raw`\sin\alpha &lt; 0`]],
        [String.raw`f'(x) = \frac{1}{x} + e^{-x}`, [String.raw`f&#x27;(x) = \frac{1}{x} + e^{-x}`]],
        [String.raw`\Delta > 0 \implies x > 0`, [String.raw`\Delta &gt; 0 \implies x &gt; 0`]],
        [String.raw`\{1, 2, \dots, n\}`, [String.raw`\{1, 2, \dots, n\}`]],
        [String.raw`\alpha = 50\%`, [String.raw`\alpha = 50\%`]],
    ];
    for (const [source, expected] of cases) {
        check(JSON.stringify(annotations(source)) === JSON.stringify(expected), `exact TeX: ${source}: ${JSON.stringify(annotations(source))}`);
        check(!math(source).includes('katex-error'), `valid rendering: ${source}`);
        check(detectDocumentNeeds(source).math, `lazy detection: ${source}`);
    }
    for (const source of [String.raw`code: \unknown`, 'Price $5 and $10.', '$5.00, $10.00', String.raw`\$5 and \$10`, '`$x$ \\alpha`', '```tex\n\\alpha\n```', String.raw`[\alpha](https://example.org/a)`, String.raw`<https://example.org/\alpha>`, 'ordinary prose, a+b=2 and f(x)', String.raw`C:\Users\name`, String.raw`\\alpha`]) {
        check(!math(source).includes('class="katex"'), `preserve non-math: ${source}`);
    }
    check(math('[ordinary](https://example.org/a?q=1)').includes('href="https://example.org/a?q=1"'), 'link target preserved');
    check(math('`\\alpha`').includes('<code>\\alpha</code>'), 'inline code preserved');
    check(math(String.raw`$\href{javascript:alert(1)}{click}$`).includes('katex'), 'untrusted TeX handled');
    check(!math(String.raw`$\href{javascript:alert(1)}{click}$`).includes('href="javascript:'), 'KaTeX trust disabled');
    check(!math(String.raw`$\htmlClass{evil}{x}$`).includes('class="evil"'), 'TeX cannot inject HTML classes');
    const html = math(fixture);
    const fixtureTex = [...html.matchAll(/<annotation encoding="application\/x-tex">([\s\S]*?)<\/annotation>/g)].map(m => m[1].trim());
    check(fixtureTex.length === 13, 'eight questions contain exactly thirteen math expressions');
    check(fixtureTex.includes(String.raw`\vec{a} = (1, 2)`), 'last question complete equation');
    check(!html.includes('katex-error'), 'eight questions have no parse errors');
    check(html.includes('<code>\\alpha</code>'), 'fixture code preserved');
    check(html.includes('$5 和 $10'), 'fixture currency preserved');
    if (original) {
        const originalHtml = math(original);
        check(!originalHtml.includes('katex-error'), 'original eight answers have no invalid formulas');
        const visible = originalHtml.replace(/<pre>[\s\S]*?<\/pre>/g, '').replace(/<code>[\s\S]*?<\/code>/g, '').replace(/<annotation[\s\S]*?<\/annotation>/g, '').replace(/<[^>]*>/g, '');
        check(!/\\(?:[A-Za-z]+|%)/.test(visible), `original eight answers leave no unrendered TeX: ${visible.match(/.{0,30}\\(?:[A-Za-z]+|%).{0,30}/g)}`);
        check(annotations(original).includes(String.raw`f&#x27;(x) = \frac{1}{x} + e^{-x}`), 'original derivative is complete');
        check(annotations(original).includes(String.raw`1 - 70\% = 30\% = 0.3`), 'original probability percent equation complete');
        check(annotations(original).includes(String.raw`g&#x27;&#x27;(x) = 0 \implies x_0 = \frac{1}{2}`), 'malformed copied-list derivative renders completely');
        check(!originalHtml.includes('<pre>'), 'copied prose artifacts are not displayed as code');
        check(math('    \\alpha\n').includes('<pre><code>\\alpha'), 'ordinary indented code remains literal');
        check(math('```text\n *      * 令 \\alpha\n```').includes('<pre><code'), 'explicit fenced prose stays code');
    }
    return { count, html };
}

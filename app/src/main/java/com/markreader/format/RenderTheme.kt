package com.markreader.format

/**
 * What a format needs to know about the surface it is rendering onto.
 *
 * One field, and deliberately a type rather than a bare `Boolean`: this is the parameter that
 * renderer settings arrive through, it is half of the render cache key, and the option-3 host
 * already has the grown-up version of the same idea in
 * [com.markreader.webview.RendererSettings]. A boolean threaded through five signatures would
 * have to be widened at every one of them.
 */
data class RenderTheme(val isDark: Boolean)

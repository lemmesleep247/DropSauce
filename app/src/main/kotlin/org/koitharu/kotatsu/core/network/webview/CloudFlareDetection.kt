package org.koitharu.kotatsu.core.network.webview

/**
 * Returns:
 * - "ok": Page cleared Cloudflare challenge.
 * - "error": Access explicitly blocked or denied.
 * - "wait": Cloudflare challenge still active/waiting.
 */
internal const val CF_STATE_JS = """
	(function(){
		try {
			var href = (document.location && document.location.href) || '';
			if (href === '' || href === 'about:blank') return 'wait';
			if (document.readyState !== 'interactive' && document.readyState !== 'complete') return 'wait';
			var t = (document.title || '').toLowerCase();
			if (t.indexOf('attention required') !== -1 || t.indexOf('access denied') !== -1) return 'error';
			if (t.indexOf('just a moment') !== -1 || t.indexOf('un instant') !== -1 ||
				t.indexOf('einen moment') !== -1 || t.indexOf('un momento') !== -1 ||
				t.indexOf('один момент') !== -1) return 'wait';
			if (document.querySelector('#challenge-running, #challenge-stage, #cf-challenge-running, .cf-browser-verification, #turnstile-wrapper, #cf-please-wait, script[src*="challenge-platform"]')) return 'wait';
			if (!document.body || document.body.children.length === 0) return 'wait';
			return 'ok';
		} catch (e) { return 'wait'; }
	})()
"""

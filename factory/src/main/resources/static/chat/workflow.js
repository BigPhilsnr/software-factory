/* Authenticate the bundled ADK client's same-origin chat transport before Angular starts. */
(() => {
  'use strict';
  const meta = document.querySelector('meta[name="factory-token"]');
  const token = meta?.content;
  meta?.remove();
  if (!token) return;
  const paths = new Set(['/run', '/run_sse']);
  const protectedRequest = (url, method) => {
    const target = new URL(url, location.href);
    return String(method).toUpperCase() === 'POST'
      && target.origin === location.origin && paths.has(target.pathname);
  };
  function report(status) {
    if (status < 400) return;
    let notice = document.getElementById('factory-command-error');
    if (!notice) {
      notice = document.createElement('div');
      notice.id = 'factory-command-error';
      notice.setAttribute('role', 'alert');
      document.body.prepend(notice);
    }
    notice.textContent = status === 403
      ? 'Command refused. Reload this page to refresh operator authorization, then retry. You can also use the operator page at /factory/.'
      : `Chat request failed (HTTP ${status}). Check the operator page at /factory/ before retrying.`;
  }
  const originalFetch = window.fetch;
  window.fetch = async function(input, init) {
    const request = input instanceof Request ? input : null;
    const authorized = protectedRequest(request ? request.url : input, init?.method || request?.method || 'GET');
    if (authorized) {
      const headers = new Headers(init?.headers ?? request?.headers);
      headers.set('X-Factory-Token', token);
      init = {...init, headers};
    }
    const response = await originalFetch.call(this, input, init);
    if (authorized) report(response.status);
    return response;
  };
  // ADK versions using Angular's XMLHttpRequest backend need the same capability.
  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSend = XMLHttpRequest.prototype.send;
  const targets = new WeakMap();
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    targets.set(this, protectedRequest(url, method));
    return originalOpen.call(this, method, url, ...rest);
  };
  XMLHttpRequest.prototype.send = function(body) {
    if (targets.get(this)) {
      this.setRequestHeader('X-Factory-Token', token);
      this.addEventListener('load', () => report(this.status), {once: true});
    }
    return originalSend.call(this, body);
  };
})();

/*
 * Hands the app the request instagram.com makes to draw its thread list, so the inbox
 * check can make the same one.
 *
 * The query is addressed by a doc_id that lives only in the site's script bundles and
 * changes whenever Instagram ships, so the app cannot know it ahead of time - but every
 * time the inbox is opened the site sends it, together with the fb_dtsg and lsd tokens the
 * call needs. Only that one request body is passed on, and nothing is ever read from a
 * response.
 *
 * `instaChatInbox` is the bridge MainActivity registers with addWebMessageListener; it
 * exists only on https://www.instagram.com, so anywhere else this does nothing. Installed
 * at document start where the WebView supports it, so the inbox's first request is seen,
 * and otherwise with the page scripts on load. Idempotent.
 */
(function () {
  'use strict';
  if (window.__instaChatCapture) return;
  window.__instaChatCapture = true;

  var bridge = window.instaChatInbox;
  if (!bridge) return;

  var MARK = 'fb_api_req_friendly_name=PolarisDirectInboxMobileQuery';

  function inspect(url, body) {
    try {
      if (String(url).indexOf('/graphql') === -1) return;
      var text = typeof body === 'string' ? body :
        (body instanceof URLSearchParams ? body.toString() : null);
      if (text && text.indexOf(MARK) !== -1) bridge.postMessage(text);
    } catch (e) {
      // Never let the app's curiosity break the site's own request.
    }
  }

  // Both, because the site sends GraphQL through each (seen on the device).
  var open = XMLHttpRequest.prototype.open;
  var send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__instaChatUrl = url;
    return open.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    inspect(this.__instaChatUrl, body);
    return send.apply(this, arguments);
  };

  var originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function (input, init) {
      inspect(typeof input === 'string' ? input : (input && input.url), init && init.body);
      return originalFetch.apply(this, arguments);
    };
  }
})();

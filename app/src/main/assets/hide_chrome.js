/*
 * Hides the parts of instagram.com that lead away from chat: the nav bar (home, explore,
 * reels, create, profile) and stray links to the feed.
 *
 * Cosmetic only. UrlPolicy.kt is what actually stops the feed from being shown; this just
 * keeps it from being one tap away. Instagram's class names are generated and change
 * without notice, so nothing here uses them - it finds things by where their links go,
 * which is part of the site's behaviour rather than its styling.
 *
 * Injected on every page load and idempotent, so running it twice is harmless.
 */
(function () {
  'use strict';
  if (window.__instaChatHidden) return;
  window.__instaChatHidden = true;

  // Pathnames of the destinations that are not chat.
  var BLOCKED = ['/', '/explore/', '/reels/', '/accounts/activity/', '/create/', '/create/select/'];

  function pathOf(anchor) {
    try {
      var url = new URL(anchor.href, location.href);
      return /(^|\.)instagram\.com$/.test(url.hostname) ? url.pathname : null;
    } catch (e) {
      return null;
    }
  }

  function hide(el) {
    el.style.setProperty('display', 'none', 'important');
    el.setAttribute('data-instachat-hidden', '');
  }

  // Never hide anything that holds the conversation itself: thread links, the message
  // box, or the main region. This is what keeps a wrong guess about the nav bar from
  // blanking the screen.
  function holdsChat(el) {
    return !!el.querySelector(
      'a[href^="/direct/t/"], textarea, [contenteditable="true"], [role="main"], main'
    );
  }

  function commonAncestor(a, b) {
    var seen = new Set();
    for (var n = a; n; n = n.parentElement) seen.add(n);
    for (var m = b; m; m = m.parentElement) if (seen.has(m)) return m;
    return null;
  }

  function sweep() {
    var explore = document.querySelector('a[href="/explore/"]');
    var reels = document.querySelector('a[href="/reels/"]');
    if (explore && reels) {
      var bar = commonAncestor(explore, reels);
      if (bar && bar !== document.body && bar !== document.documentElement && !holdsChat(bar)) {
        hide(bar);
      }
    }

    var links = document.querySelectorAll('a[href]:not([data-instachat-hidden])');
    for (var i = 0; i < links.length; i++) {
      var path = pathOf(links[i]);
      if (path !== null && BLOCKED.indexOf(path) !== -1 && !holdsChat(links[i])) {
        hide(links[i]);
      }
    }
  }

  // The site renders late and re-renders often, so sweep again whenever the DOM changes,
  // at most once a frame.
  var queued = false;
  function schedule() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      sweep();
    });
  }

  sweep();
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true,
  });
})();

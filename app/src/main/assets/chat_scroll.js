/*
 * Returns to the same place in a chat after looking at a post or reel opened from it.
 *
 * Opening a shared item navigates to its page (/p/<code>/, /reel/<code>/), and coming
 * back makes the site build the thread afresh, scrolled to the newest message - so a reel
 * found far up a conversation cost the scroll back up to find where you were. Seen on the
 * S23: 573px up, a /p/ page, back at 0.
 *
 * So while a chat is on screen its distance from the bottom is remembered, and when the
 * very same chat comes back straight from an item page, it is scrolled to again. Older
 * messages are only loaded as the list nears its top, so the distance may not exist yet:
 * the restore keeps scrolling as far as it can, which makes the site load the next page,
 * until it gets there or the list stops growing. Touching the screen ends it at once.
 *
 * Opening a chat any other way - from the inbox, a notification - still starts at the
 * newest message, as the site intends. Kept in sessionStorage, because a return the
 * WebView cannot make with goBack() reloads the page (ChatWebViewClient.returnTo).
 *
 * Injected on every page load and idempotent.
 */
(function () {
  'use strict';
  if (window.__instaChatScroll) return;
  window.__instaChatScroll = true;

  var CHAT = /^\/direct\/t\/[^/]+\/?$/;
  var ITEM = /^\/(?:[A-Za-z0-9._]+\/)?(?:p|reel|reels|tv)\/[A-Za-z0-9_-]+\/?$/;
  var KEY_OFFSETS = 'instachat.chatOffsets';
  var KEY_RETURN = 'instachat.returnTo';

  // How long a restore may keep asking for older messages, and how close counts as there.
  var RESTORE_MS = 10000;
  var STEP_MS = 250;
  var CLOSE_PX = 4;

  function read(key) {
    try { return JSON.parse(sessionStorage.getItem(key) || 'null'); } catch (e) { return null; }
  }

  function write(key, value) {
    try { sessionStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* full: lose it */ }
  }

  function chatPath(path) {
    return CHAT.test(path) ? path.replace(/\/?$/, '/') : null;
  }

  /**
   * The message list: the tallest vertically scrollable element. The thread view has no
   * other scroller anywhere near its height, and class names cannot be used - they are
   * generated.
   */
  function scroller() {
    var best = null;
    var all = document.querySelectorAll('div');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.scrollHeight <= el.clientHeight + 1) continue;
      var overflow = getComputedStyle(el).overflowY;
      if (overflow !== 'auto' && overflow !== 'scroll') continue;
      if (!best || el.scrollHeight > best.scrollHeight) best = el;
    }
    return best;
  }

  // The list is laid out column-reverse: scrollTop is 0 at the newest message and goes
  // negative going up. Handled both ways in case the site ever flips it back.
  function reversed(el) {
    return getComputedStyle(el).flexDirection === 'column-reverse';
  }

  function fromBottom(el) {
    return reversed(el) ? -el.scrollTop : el.scrollHeight - el.clientHeight - el.scrollTop;
  }

  function setFromBottom(el, distance) {
    el.scrollTop = reversed(el) ? -distance : el.scrollHeight - el.clientHeight - distance;
  }

  var restoring = null;
  var lastPath = null;

  function remember() {
    var path = chatPath(location.pathname);
    // Not before onPathChange has seen this page: a chat rebuilt on return scrolls itself
    // to the bottom first, and saving that would overwrite the very offset to restore.
    if (!path || restoring || chatPath(lastPath || '') !== path) return;
    var el = scroller();
    if (!el) return;
    var offsets = read(KEY_OFFSETS) || {};
    offsets[path] = Math.max(0, Math.round(fromBottom(el)));
    write(KEY_OFFSETS, offsets);
  }

  function stopRestoring() {
    if (!restoring) return;
    clearInterval(restoring.timer);
    restoring = null;
  }

  function restore(path, distance) {
    stopRestoring();
    if (!distance) return;
    var started = Date.now();
    var lastHeight = -1;
    var stalled = 0;
    restoring = {
      timer: setInterval(function () {
        var el = scroller();
        if (chatPath(location.pathname) !== path || Date.now() - started > RESTORE_MS) {
          stopRestoring();
          return;
        }
        if (!el) return;
        setFromBottom(el, distance);
        if (Math.abs(fromBottom(el) - distance) <= CLOSE_PX) {
          stopRestoring();
          return;
        }
        // Short of it: the scroll has gone as far as the loaded messages allow, which is
        // what makes the site fetch older ones. Give up once that stops adding any.
        if (el.scrollHeight === lastHeight) {
          stalled++;
          if (stalled * STEP_MS > 3000) stopRestoring();
        } else {
          stalled = 0;
          lastHeight = el.scrollHeight;
        }
      }, STEP_MS)
    };
  }

  function onPathChange() {
    var path = location.pathname;
    if (path === lastPath) return;
    var previous = lastPath;
    lastPath = path;

    if (ITEM.test(path)) {
      // Leaving a chat for something shared in it: that chat is the one to come back to.
      // Two items in a row (an item opened from an item) keep the original chat.
      var from = previous && chatPath(previous);
      if (from) write(KEY_RETURN, from);
      return;
    }

    var chat = chatPath(path);
    var returnTo = read(KEY_RETURN);
    write(KEY_RETURN, null);
    if (chat && chat === returnTo) {
      var offsets = read(KEY_OFFSETS) || {};
      restore(chat, offsets[chat]);
    } else {
      stopRestoring();
    }
  }

  // Scroll events do not bubble; capturing at the document sees the list's own.
  var pending = false;
  document.addEventListener('scroll', function () {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () {
      pending = false;
      remember();
    });
  }, true);

  // The user's own scroll always wins over a restore still under way.
  window.addEventListener('touchstart', stopRestoring, {passive: true, capture: true});
  window.addEventListener('wheel', stopRestoring, {passive: true, capture: true});

  // The site is a single-page app: most navigations only change the URL and the DOM.
  var scheduled = false;
  new MutationObserver(function () {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      scheduled = false;
      onPathChange();
    });
  }).observe(document.documentElement, {childList: true, subtree: true});
  window.addEventListener('popstate', onPathChange);

  onPathChange();
})();

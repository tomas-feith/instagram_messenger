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

  /*
   * Reel lock. A reel shared in a chat opens on the single-reel page, but that page loads
   * suggested reels underneath and lets you swipe on into them - without a navigation
   * UrlPolicy can refuse. So while a reel is on screen, vertical movement is switched off:
   * the page and every scroll container are frozen, and vertical swipes and wheel events
   * are swallowed before the site's own swipe handling sees them. Taps (play, pause, mute)
   * and horizontal drags (the progress bar) pass through untouched. Dialogs - the comments
   * sheet - keep their scrolling.
   *
   * Everything checks the current path at the moment it acts, so returning to the chat,
   * which is the same document, unlocks it again.
   */
  var REEL_PATH = /^\/(?:[A-Za-z0-9._]+\/)?(?:reel|reels)\/[A-Za-z0-9_-]+\/?$/;
  var LOCKED_ATTR = 'data-instachat-locked';
  var pageLocked = false;
  // The root elements' own inline values for the properties the lock sets, so unlocking
  // restores them rather than deleting them. Only these four are saved and put back: the
  // site edits other inline styles on body while a reel is open, and those must stand.
  var ROOT_PROPS = ['overflow', 'overflow-x', 'overflow-y', 'overscroll-behavior'];
  var savedRoots = [];

  function onReel() {
    return REEL_PATH.test(location.pathname);
  }

  function inDialog(target) {
    return !!(target && target.closest && target.closest('[role="dialog"]'));
  }

  function updateReelLock() {
    var locked = onReel();
    // Inline !important on the root elements, not a stylesheet: the site's own rules set
    // overflow-y on html with a more specific selector, and inline !important beats any
    // stylesheet rule regardless of specificity.
    var roots = [document.documentElement, document.body].filter(Boolean);
    if (locked && !pageLocked) {
      pageLocked = true;
      savedRoots = roots.map(function (el) {
        var saved = ROOT_PROPS.map(function (prop) {
          return [prop, el.style.getPropertyValue(prop), el.style.getPropertyPriority(prop)];
        });
        el.style.setProperty('overflow', 'hidden', 'important');
        el.style.setProperty('overscroll-behavior', 'none', 'important');
        return { el: el, saved: saved };
      });
    } else if (!locked && pageLocked) {
      pageLocked = false;
      savedRoots.forEach(function (root) {
        ROOT_PROPS.forEach(function (prop) { root.el.style.removeProperty(prop); });
        root.saved.forEach(function (entry) {
          if (entry[1]) root.el.style.setProperty(entry[0], entry[1], entry[2]);
        });
      });
      savedRoots = [];
      var frozen = document.querySelectorAll('[' + LOCKED_ATTR + ']');
      for (var i = 0; i < frozen.length; i++) {
        // Put back exactly what the site had inline. Removing `overflow` outright would
        // also wipe an inline overflow-y the site set itself, and leave a chat list that
        // no longer scrolls.
        frozen[i].style.cssText = frozen[i].getAttribute(LOCKED_ATTR);
        frozen[i].removeAttribute(LOCKED_ATTR);
      }
    }
    if (!locked) return;

    var all = document.body
      ? document.body.querySelectorAll('*:not([' + LOCKED_ATTR + '])')
      : [];
    for (var j = 0; j < all.length; j++) {
      var el = all[j];
      if (el.scrollHeight <= el.clientHeight || inDialog(el)) continue;
      var overflowY = getComputedStyle(el).overflowY;
      if (overflowY === 'auto' || overflowY === 'scroll') {
        el.setAttribute(LOCKED_ATTR, el.style.cssText);
        el.style.setProperty('overflow', 'hidden', 'important');
      }
    }
  }

  var touchX = 0;
  var touchY = 0;
  window.addEventListener('touchstart', function (e) {
    if (e.touches.length) {
      touchX = e.touches[0].clientX;
      touchY = e.touches[0].clientY;
    }
  }, { capture: true, passive: true });

  function isVerticalSwipe(x, y) {
    return Math.abs(y - touchY) > Math.abs(x - touchX);
  }

  window.addEventListener('touchmove', function (e) {
    if (!onReel() || !e.touches.length || inDialog(e.target)) return;
    if (isVerticalSwipe(e.touches[0].clientX, e.touches[0].clientY)) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, { capture: true, passive: false });

  // Some swipe code listens to pointer events rather than touch events.
  window.addEventListener('pointermove', function (e) {
    if (!onReel() || e.pointerType !== 'touch' || inDialog(e.target)) return;
    if (isVerticalSwipe(e.clientX, e.clientY)) e.stopPropagation();
  }, { capture: true });

  window.addEventListener('wheel', function (e) {
    if (!onReel() || inDialog(e.target)) return;
    e.preventDefault();
    e.stopPropagation();
  }, { capture: true, passive: false });

  // The site renders late and re-renders often, so sweep again whenever the DOM changes,
  // at most once a frame. Navigating between the chat and a reel always changes the DOM,
  // so this is also what switches the reel lock on and off.
  var queued = false;
  function schedule() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      sweep();
      updateReelLock();
    });
  }

  window.addEventListener('popstate', schedule);

  sweep();
  updateReelLock();
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true,
  });
})();

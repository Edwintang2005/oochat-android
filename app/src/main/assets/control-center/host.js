'use strict';

// The Control Center parent page. It frames the agent's app and relays the app's private
// MessagePort to the app's Kotlin side (ControlCenterPage), which owns the bridge itself.
(function () {
  var PREFIX = 'connectonion.control-center/';
  var nativeSide = window.ochatNative;
  var frame = document.getElementById('app');
  var app = null;
  var port = null;

  if (!nativeSide || !frame) return;

  function toNative(message) {
    nativeSide.postMessage(JSON.stringify(message));
  }

  function closePort() {
    if (port) {
      port.onmessage = null;
      port.close();
      port = null;
    }
  }

  nativeSide.onmessage = function (event) {
    var message;
    try {
      message = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    if (message.kind === 'load') {
      closePort();
      app = { origin: message.origin, revision: message.revision };
      frame.src = message.url + '#co-parent=' + encodeURIComponent(location.origin) +
        '&co-revision=' + encodeURIComponent(message.revision);
    } else if (message.kind === 'connect' && app) {
      closePort();
      var channel = new MessageChannel();
      port = channel.port1;
      port.onmessage = function (e) {
        var data;
        try {
          data = JSON.stringify(e.data);
        } catch (err) {
          return;
        }
        if (typeof data === 'string') toNative({ kind: 'message', data: data });
      };
      frame.contentWindow.postMessage(
        { type: PREFIX + 'connect', version: 1, revision: app.revision, epoch: message.epoch },
        app.origin,
        [channel.port2]
      );
    } else if (message.kind === 'message' && port) {
      port.postMessage(JSON.parse(message.data));
    } else if (message.kind === 'close') {
      closePort();
    }
  };

  // Only the framed app, from its own origin, asking for this revision.
  window.addEventListener('message', function (event) {
    var data = event.data;
    if (!app || event.source !== frame.contentWindow || event.origin !== app.origin) return;
    if (!data || data.type !== PREFIX + 'ready' || data.version !== 1 || data.revision !== app.revision) return;
    toNative({ kind: 'app-ready' });
  });

  // WebView does not ask native about https frame navigations; the CSP refusing one is the signal.
  document.addEventListener('securitypolicyviolation', function (event) {
    if (event.effectiveDirective === 'frame-src') toNative({ kind: 'blocked' });
  });

  toNative({ kind: 'page-ready' });
})();

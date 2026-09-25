/* ==========================================================================
   Proxy 落地页脚本
   - 主题切换（跟随系统 / 手动）
   - 背景粒子动画（修复旧版 setInterval(…, 0) 死循环与颜色拼接错误）
   - 安装源标签页与代码复制
   ========================================================================== */
(function () {
  'use strict';

  var THEME_KEY = 'px_landing_theme';
  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ------------------------------------------------------------------ *
   * 主题
   * ------------------------------------------------------------------ */
  var ICON_SUN = '<path d="M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10"/><path d="M12 1v3M12 20v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M1 12h3M20 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/>';
  var ICON_MOON = '<path d="M21 13A9 9 0 1 1 11 3a7 7 0 0 0 10 10z"/>';

  function readTheme() {
    try {
      var saved = localStorage.getItem(THEME_KEY);
      if (saved === 'light' || saved === 'dark') return saved;
    } catch (e) { /* 忽略隐私模式 */ }
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.style.colorScheme = theme;
    var icon = document.getElementById('themeIcon');
    if (icon) icon.innerHTML = theme === 'dark' ? ICON_MOON : ICON_SUN;
    var button = document.getElementById('themeToggle');
    if (button) button.setAttribute('aria-label', theme === 'dark' ? '切换到浅色主题' : '切换到深色主题');
    if (window.__pxParticles) window.__pxParticles.setTheme(theme);
  }

  function initTheme() {
    applyTheme(readTheme());
    var button = document.getElementById('themeToggle');
    if (!button) return;
    button.addEventListener('click', function () {
      var next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* 忽略 */ }
      applyTheme(next);
    });
  }

  /* ------------------------------------------------------------------ *
   * 背景粒子
   * ------------------------------------------------------------------ */
  function initParticles() {
    var canvas = document.getElementById('canvas');
    if (!canvas || reduceMotion) return;

    var ctx = canvas.getContext('2d');
    var particles = [];
    var width = 0;
    var height = 0;
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var maxParticles = 90;
    var linkDistance = 118;
    var running = true;
    var theme = document.documentElement.getAttribute('data-theme') || 'light';
    var frame = null;

    function palette() {
      if (theme === 'dark') {
        return { dot: '255, 255, 255', line: '148, 178, 255' };
      }
      return { dot: '47, 107, 255', line: '47, 107, 255' };
    }

    function create(x, y) {
      return {
        x: x,
        y: y,
        vx: (Math.random() - 0.5) * 0.45,
        vy: (Math.random() - 0.5) * 0.45,
        r: 1 + Math.random() * 1.6
      };
    }

    function resize() {
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      canvas.style.width = width + 'px';
      canvas.style.height = height + 'px';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

      // 依据可视面积决定粒子数量，移动端自动减少
      var target = Math.round((width * height) / 24000);
      target = Math.max(18, Math.min(maxParticles, target));

      particles = [];
      for (var i = 0; i < target; i++) {
        particles.push(create(Math.random() * width, Math.random() * height));
      }
    }

    function step() {
      frame = null;
      if (!running) return;

      var colors = palette();
      ctx.clearRect(0, 0, width, height);

      for (var i = 0; i < particles.length; i++) {
        var p = particles[i];
        p.x += p.vx;
        p.y += p.vy;

        if (p.x < -10) p.x = width + 10;
        if (p.x > width + 10) p.x = -10;
        if (p.y < -10) p.y = height + 10;
        if (p.y > height + 10) p.y = -10;

        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(' + colors.dot + ', 0.55)';
        ctx.fill();

        for (var j = i + 1; j < particles.length; j++) {
          var q = particles[j];
          var dx = p.x - q.x;
          var dy = p.y - q.y;
          var dist = Math.sqrt(dx * dx + dy * dy);
          if (dist > linkDistance) continue;
          var alpha = (1 - dist / linkDistance) * 0.35;
          ctx.beginPath();
          ctx.strokeStyle = 'rgba(' + colors.line + ', ' + alpha.toFixed(3) + ')';
          ctx.lineWidth = 1;
          ctx.moveTo(p.x, p.y);
          ctx.lineTo(q.x, q.y);
          ctx.stroke();
        }
      }

      frame = window.requestAnimationFrame(step);
    }

    function start() {
      if (frame === null) frame = window.requestAnimationFrame(step);
    }

    function stop() {
      if (frame !== null) {
        window.cancelAnimationFrame(frame);
        frame = null;
      }
    }

    resize();
    start();

    window.addEventListener('resize', function () {
      window.clearTimeout(canvas.__resizeTimer);
      canvas.__resizeTimer = window.setTimeout(resize, 160);
    });

    document.addEventListener('visibilitychange', function () {
      running = !document.hidden;
      if (running) start();
      else stop();
    });

    window.__pxParticles = {
      setTheme: function (next) { theme = next; }
    };
  }

  /* ------------------------------------------------------------------ *
   * 安装源标签页
   * ------------------------------------------------------------------ */
  function initTabs() {
    var tabs = Array.prototype.slice.call(document.querySelectorAll('[data-tab]'));
    if (!tabs.length) return;
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        var name = tab.getAttribute('data-tab');
        tabs.forEach(function (other) {
          var active = other === tab;
          other.classList.toggle('is-active', active);
          other.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        Array.prototype.slice.call(document.querySelectorAll('[data-panel]')).forEach(function (panel) {
          panel.hidden = panel.getAttribute('data-panel') !== name;
        });
      });
    });
  }

  /* ------------------------------------------------------------------ *
   * 代码复制
   * ------------------------------------------------------------------ */
  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', 'readonly');
      area.style.position = 'fixed';
      area.style.left = '-999rem';
      document.body.appendChild(area);
      area.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
      area.remove();
      ok ? resolve() : reject(new Error('copy failed'));
    });
  }

  function initCopy() {
    Array.prototype.slice.call(document.querySelectorAll('[data-copy-btn]')).forEach(function (button) {
      button.addEventListener('click', function () {
        var text = '';
        var host = button.closest('[data-copy]');
        if (host) {
          text = host.getAttribute('data-copy') || '';
        } else {
          var targetSelector = button.getAttribute('data-copy-target');
          var target = targetSelector ? document.querySelector(targetSelector) : null;
          if (target) text = target.textContent;
        }
        if (!text) return;
        copyText(text).then(function () {
          button.textContent = '已复制';
        }).catch(function () {
          button.textContent = '请手动复制';
        }).then(function () {
          window.setTimeout(function () { button.textContent = '复制'; }, 1800);
        });
      });
    });
  }

  function init() {
    initTheme();
    initParticles();
    initTabs();
    initCopy();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

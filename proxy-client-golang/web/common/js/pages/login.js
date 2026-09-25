/* 登录 / 注册页逻辑 */
(function () {
  'use strict';
  var PX = window.PX;

  var FEATURES = [
    { icon: 'rocket', title: '多端支持', desc: 'Windows / Linux / 安卓' },
    { icon: 'globe', title: '二级域名', desc: '免费赠送自定义域名' },
    { icon: 'server', title: '固定端口', desc: '随机或指定外网端口' },
    { icon: 'shieldCheck', title: 'HTTPS 加密', desc: '支持自定义 SSL 证书' }
  ];

  function showForm(which) {
    var loginForm = document.getElementById('loginForm');
    var registerForm = document.getElementById('registerForm');
    var tabLogin = document.getElementById('tabLogin');
    var tabRegister = document.getElementById('tabRegister');
    var isLogin = which === 'login';
    loginForm.classList.toggle('hidden', !isLogin);
    registerForm.classList.toggle('hidden', isLogin);
    tabLogin.classList.toggle('is-active', isLogin);
    tabRegister.classList.toggle('is-active', !isLogin);
    tabLogin.setAttribute('aria-selected', isLogin ? 'true' : 'false');
    tabRegister.setAttribute('aria-selected', isLogin ? 'false' : 'true');
  }

  function renderDonations(payload) {
    var host = document.getElementById('pay');
    PX.clear(host);
    var list = payload && payload.data;
    if (!Array.isArray(list) || !list.length) {
      host.appendChild(PX.el('div', { class: 'donor', text: '还没有支持者，期待你的到来' }));
      return;
    }
    list.slice(0, 8).forEach(function (item) {
      // 全部使用文本节点写入，云端口径的数据不会进入 HTML 解析。
      host.appendChild(PX.el('div', { class: 'donor' }, [
        PX.el('span', { class: 'truncate', title: String(item.username || '匿名'), text: item.username || '匿名' }),
        PX.el('span', { class: 'donor__amount', text: '￥' + (item.price === undefined || item.price === null ? '0' : item.price) })
      ]));
    });
  }

  function wireRegisterCountdown(button) {
    var remain = 0;
    var timer = null;

    function tick() {
      if (remain <= 0) {
        clearInterval(timer);
        button.disabled = false;
        button.textContent = '发送验证码';
        return;
      }
      button.disabled = true;
      button.textContent = remain + ' 秒后重发';
      remain -= 1;
    }

    return function start() {
      remain = 60;
      tick();
      timer = setInterval(tick, 1000);
    };
  }

  function init() {
    PX.user.migrate();

    ['asideMark', 'mainMark'].forEach(function (id) {
      var node = document.getElementById(id);
      if (!node) return;
      PX.clear(node);
      node.appendChild(PX.iconNode('shieldCheck'));
    });

    var themeSlot = document.getElementById('themeSlot');
    if (themeSlot) themeSlot.appendChild(PX.theme.control());

    var featureHost = document.getElementById('featureList');
    if (featureHost) {
      FEATURES.forEach(function (feature) {
        featureHost.appendChild(PX.el('div', { class: 'auth__feature' }, [
          PX.el('span', { icon: feature.icon }),
          PX.el('h3', { text: feature.title }),
          PX.el('p', { text: feature.desc })
        ]));
      });
    }

    if (PX.user.profile()) {
      location.replace('center.html');
      return;
    }

    // 提前建立控制台会话，令牌无效时立即给出提示。
    PX.api.bootstrap().catch(function (err) {
      PX.toastErr(err.message || '控制台会话建立失败', '无法连接控制台');
    });

    document.getElementById('tabLogin').addEventListener('click', function () { showForm('login'); });
    document.getElementById('tabRegister').addEventListener('click', function () { showForm('register'); });

    var loginForm = document.getElementById('loginForm');
    var loginButton = document.getElementById('login_btn');
    var registerButton = document.getElementById('reg_btn');
    var sendButton = document.getElementById('send_email');
    var startCountdown = wireRegisterCountdown(sendButton);

    loginForm.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var username = document.getElementById('login_username').value.trim();
      var password = document.getElementById('login_password').value;
      if (!username || !password) {
        PX.toastWarn('请输入邮箱和密码');
        return;
      }
      loginButton.disabled = true;
      loginButton.textContent = '登录中…';
      PX.user.login(username, password).then(function () {
        PX.toastOk('登录成功，正在进入控制台');
        setTimeout(function () { location.replace('center.html'); }, 300);
      }).catch(function (err) {
        PX.toastErr(err && err.message ? err.message : '登录失败', '登录失败');
      }).then(function () {
        loginButton.disabled = false;
        loginButton.textContent = '登录';
      });
    });

    sendButton.addEventListener('click', function () {
      var username = document.getElementById('reg_username').value.trim();
      if (!username) {
        PX.toastWarn('请先填写邮箱');
        return;
      }
      sendButton.disabled = true;
      sendButton.textContent = '发送中…';
      PX.api.get('/hp/user/email', { username: username }).then(function (data) {
        if (!PX.isOk(data)) throw new PX.ApiError(PX.msgOf(data, '发送失败'), 200, data);
        PX.toastOk('验证码已发送，请查收邮件');
        startCountdown();
      }).catch(function (err) {
        sendButton.disabled = false;
        sendButton.textContent = '发送验证码';
        PX.toastErr(err && err.message ? err.message : '发送失败');
      });
    });

    document.getElementById('registerForm').addEventListener('submit', function (ev) {
      ev.preventDefault();
      var username = document.getElementById('reg_username').value.trim();
      var password = document.getElementById('reg_password').value;
      var code = document.getElementById('reg_code').value.trim();
      if (!username || !password || !code) {
        PX.toastWarn('请填写完整信息');
        return;
      }
      if (password.length < 6) {
        PX.toastWarn('密码至少 6 位');
        return;
      }
      registerButton.disabled = true;
      registerButton.textContent = '提交中…';
      PX.api.post('/hp/user/reg', { username: username, password: password, code: code }).then(function (data) {
        if (!PX.isOk(data)) throw new PX.ApiError(PX.msgOf(data, '注册失败'), 200, data);
        PX.toastOk('注册成功，请登录');
        document.getElementById('login_username').value = username;
        document.getElementById('login_password').value = '';
        showForm('login');
      }).catch(function (err) {
        PX.toastErr(err && err.message ? err.message : '注册失败');
      }).then(function () {
        registerButton.disabled = false;
        registerButton.textContent = '注册 / 重置密码';
      });
    });

    PX.api.get('/hp/server/pay').then(renderDonations).catch(function () {
      var host = document.getElementById('pay');
      PX.clear(host);
      host.appendChild(PX.el('div', { class: 'donor', text: '支持者列表加载失败' }));
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

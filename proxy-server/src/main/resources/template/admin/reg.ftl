<#include "./header.ftl">
<#--  注册管理
      model（RegController#reg）：time（Integer，ConstConfig.TIME）
      表单 method/action/字段名（time）与原实现完全一致。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">注册管理</h1>
            <div class="page-head__desc">控制客户端自助注册的开关与开放时段。</div>
        </div>
    </div>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="key" data-icon-class="icon--sm"></span>
                <span>注册模式</span>
            </div>
        </div>
        <form method="post" action="/admin/setTime">
            <div class="card__body">
                <div class="field mb-0">
                    <label class="field__label" for="reg-time">
                        注册模式：-1免费关闭注册 0 免费注册 &gt;0 每天24小时时间内注册(小时数)
                    </label>
                    <input class="input" id="reg-time" name="time" placeholder="时间模式" type="number"
                           value="${(time!0)?c}" data-autofocus/>
                    <span class="field__hint">保存后立即生效，无需重启服务。</span>
                </div>
            </div>
            <div class="card__foot row row--end">
                <button type="submit" class="btn btn--primary">确定</button>
            </div>
        </form>
    </section>
</main>
<script>
    Admin.setTitle('注册管理');
</script>
</body>
</html>

<#include "./header.ftl">
<#--  公告管理
      model（TipsController#tips）：tips（String，ConstConfig.TIPS）
      表单 method/action/字段名（tips）与原实现完全一致。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">公告管理</h1>
            <div class="page-head__desc">该公告会下发给客户端展示。</div>
        </div>
    </div>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="info" data-icon-class="icon--sm"></span>
                <span>公告内容</span>
            </div>
        </div>
        <form method="post" action="/admin/setTips">
            <div class="card__body">
                <div class="field mb-0">
                    <label class="field__label" for="tips-input">公告内容</label>
                    <input class="input" id="tips-input" name="tips" placeholder="公告内容" type="text"
                           value="${(tips!"")?html}" data-autofocus/>
                    <span class="field__hint">留空即不展示公告。</span>
                </div>
            </div>
            <div class="card__foot row row--end">
                <button type="submit" class="btn btn--primary">确定</button>
            </div>
        </form>
    </section>
</main>
<script>
    Admin.setTitle('公告管理');
</script>
</body>
</html>

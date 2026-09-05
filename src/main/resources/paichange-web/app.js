'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let key = '', selected = null, snapshot = null, busy = false, demo = false, creation = null, creationId = null, generation = 0;
  let pollTimer = null, pollDelay = 1000, polling = true, lastVersion = null;
  const terminal = new Set(['SPEC_REVIEW', 'DELIVERY_REVIEW', 'COMPLETED', 'FAILED', 'REJECTED', 'CANCELED']);
  const messages = {400:'请求字段有误，请检查输入。',401:'认证失败，请重新输入 API Key。',403:'操作被拒绝，请检查审批身份或产物访问边界。',404:'任务或产物不存在，请刷新列表。',409:'内容已变化。已刷新，请重新核对并确认；未重放审批。',422:'不满足业务条件，不能通过普通交付审批覆盖 Verdict。',500:'服务处理失败。请刷新任务和事件确认已持久化进度，勿假定操作成功。'};
  function element(tag, text, className) { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; }
  function notice(text, error = false) { $('notice').textContent = text; $('notice').dataset.error = String(error); }
  async function api(path, body) {
    const response = await fetch('/v1/changes' + path, {method:body === undefined ? 'GET':'POST', cache:'no-store', headers:{Authorization:'Bearer ' + key, 'Content-Type':'application/json'}, body:body === undefined ? undefined:JSON.stringify(body)});
    const json = await response.json();
    if (!response.ok) { const error = new Error(messages[response.status] || '请求失败，请检查服务。'); error.status = response.status; throw error; }
    return json;
  }
  function showError(error) { notice(error.message || '连接失败，请检查本地服务。', true); }
  function pane(parent, title, data, open = false) { const d = element('details'); d.open = open; d.append(element('summary', title)); const p = element('pre', typeof data === 'string' ? data:JSON.stringify(data, null, 2)); d.append(p); parent.append(d); return p; }
  function field(parent, title, value = '') { const label = element('label', title); const input = element('input'); input.value = value; label.append(input); parent.append(label); return input; }
  function button(parent, title, action, style = '') { const b = element('button', title, style); b.type = 'button'; b.addEventListener('click', action); parent.append(b); return b; }
  async function list() {
    const data = await api(''); $('list').replaceChildren();
    for (const task of data.changes) {
      const b = button($('list'), task.title, () => guarded(() => load(task.changeId)), 'task' + (task.changeId === selected ? ' selected':''));
      b.append(element('small', task.state + ' · ' + (task.deliveryVerdict || task.run?.verdict || '待验证')));
    }
    if (!data.changes.length) $('list').append(element('p','暂无任务。展开下方创建入口。','muted'));
  }
  async function load(id) {
    const ticket = ++generation;
    if (selected !== id) { pollDelay = 1000; lastVersion = null; }
    selected = id; location.hash = id; snapshot = null;
    $('detail').replaceChildren(element('p','正在读取契约与 Evidence…'));
    try {
      const task = await api('/' + encodeURIComponent(id));
      const [artifacts, events] = await Promise.all([api('/' + encodeURIComponent(id) + '/artifacts'), api('/' + encodeURIComponent(id) + '/events')]);
      if (ticket !== generation) return;
      if (task.version !== artifacts.version) { notice('任务正在推进，请刷新后查看一致快照。'); return; }
      pollDelay = lastVersion === task.version ? Math.min(10000, pollDelay * 2) : 1000;
      lastVersion = task.version;
      snapshot = task;
      render(task, artifacts, events.events);
    } catch (error) {
      if (ticket === generation) { snapshot = null; $('detail').replaceChildren(element('p','内容读取失败，审批已禁用。请刷新。','bad')); }
      throw error;
    }
  }
  function render(task, artifacts, events) {
    const root = $('detail'); root.replaceChildren();
    if (task.changeId === creationId) showCreationState(task);
    root.append(element('p',task.changeId + ' · version ' + task.version,'eyebrow'), element('h2',task.title),element('p',task.requirement),element('span',task.state,'badge'));
    draftControls(root, task);
    const facts = element('div', undefined, 'facts');
    const verdict = task.deliveryVerdict || task.run?.verdict;
    const verified = task.run?.status === 'FINISHED' && verdict === 'PASSED';
    const completed = task.state === 'COMPLETED' && verified && task.delivery?.conclusion === 'success';
    for (const [name,value,good] of [
      ['Worker',task.run ? '执行已结束' : (['RUNNING','VERIFYING'].includes(task.state)?'执行中':'尚无结束结果'),!!task.run],
      ['当前交付判断',verdict || '尚未验证',verified],
      ['Delivery Approval',(task.deliveryApprovalValid ? task.deliveryApproval?.decision : task.deliveryApproval ? '已失效' : '') || (task.route?.deliveryApprovalRequired === false?'路由明确免批':'尚未批准'),task.deliveryApprovalValid === true],
      ['发布',completed?'发布完成':task.state === 'PUBLISHING'?'等待发布 / 重试':task.delivery?.conclusion === 'pending'?'Mock pending':task.delivery?.conclusion === 'failure'?'Mock failure':'尚未完成',completed]]) {
      const f = element('div',name,'fact'); f.append(element('strong',value,good?'good':'warn')); facts.append(f);
    }
    root.append(facts);
    pane(root,'工单与仓库',{source:task.source,repository:task.repository,requester:task.requesterId});
    pane(root,'风险与 ExecutionRoute',{risk:task.risk,route:task.route},true);
    root.append(element('p','toolPolicy 仅为已记录的 profile；组织白名单与 Worker HITL 尚未接通。','boundary'));
    pane(root,'当前 Draft · r' + (task.spec?.revision || '—'),artifacts.draft || '尚无 Draft',true);
    if (artifacts.lockedSpec) pane(root,'锁定 Spec · ' + task.spec.digest,artifacts.lockedSpec);
    const revisionPane = element('details'); revisionPane.open = artifacts.revisions.length > 1; revisionPane.append(element('summary','Spec revision diff'));
    const controls = element('div',undefined,'revision-controls');
    const selects = ['From revision','To revision'].map((name,index) => { const label = element('label',name), select = element('select'); for (const r of artifacts.revisions) { const option = element('option','r' + r.revision); option.value = r.revision; select.append(option); } select.value = index ? artifacts.toRevision : artifacts.fromRevision; label.append(select); controls.append(label); return select; });
    const diff = element('pre',artifacts.revisionDiff || '尚无 revision');
    button(controls,'比较',() => guarded(async () => { const data = await api('/' + task.changeId + '/artifacts?fromRevision=' + selects[0].value + '&toRevision=' + selects[1].value); diff.textContent = data.revisionDiff; }), 'secondary');
    revisionPane.append(controls,diff); root.append(revisionPane);
    pane(root,'Verifier 与 Acceptance Criteria',{verifiers:artifacts.verifiers,criteria:artifacts.criteria});
    pane(root,'代码 diff' + (artifacts.result?.workspace?.diffTruncated ? '（已截断）':''),artifacts.codeDiff || '执行结束后保存最终 diff',!!task.run);
    pane(root,'原始 Run Verdict / Criterion Results',{verdict:task.run?.verdict,criteria:artifacts.criterionResults});
    pane(root,'当前交付判断 · revision ' + (task.judgmentRevision || 0),task.humanReview?.judgments?.slice(-1)[0] || {verdict});
    humanReview(root, task, artifacts);
    pane(root,'验证轮次与 Evidence',{repairCount:artifacts.result?.metrics?.repairCount,verificationAttempts:artifacts.verificationAttempts,evidence:artifacts.evidence},!!task.run);
    if (task.state === 'SPEC_REVIEW' || task.state === 'DELIVERY_REVIEW') approval(root, task);
    pane(root,'审批记录',{specApproval:task.specApproval,deliveryApproval:task.deliveryApproval});
    pane(root,'Mock PR Check',task.delivery || '尚未发布 Mock Check',true);
    pane(root,'人工验收与判断历史',task.humanReview || '尚无人工补录');
    pane(root,'Mock Check 发布历史',task.deliveryHistory || []);
    if (verdict === 'NEEDS_HUMAN') root.append(element('p','NEEDS_HUMAN：请逐项完成人工验收；Delivery Approval 不能代替验收。','boundary'));
    const timeline = element('details'); timeline.open = true; timeline.append(element('summary','事件时间线'));
    const ol = element('ol',undefined,'timeline');
    for (const event of events) { const li = element('li',event.type); li.append(element('small',event.createdAt + ' · ' + event.actorId + ' · ' + event.previousState + ' → ' + event.newState),element('pre',event.payloadJson)); ol.append(li); }
    timeline.append(ol); root.append(timeline);
  }
  function humanReview(root, task, artifacts) {
    if (!task.run) return;
    const criteria = artifacts.criteria.filter(c => c.oracle?.type?.toUpperCase() === 'HUMAN');
    if (!criteria.length) return;
    const panel = element('section', undefined, 'actions human-review');
    panel.append(element('h3','人工验收 · Human Evidence'),element('p','逐项记录观察结果。更正会保留历史并使旧交付审批失效；HIGH 风险通过验收后仍需 Delivery Approval。'));
    panel.append(element('p','绑定 version ' + task.version + ' · digest ' + task.spec.digest + ' · run ' + task.run.runId + ' · head ' + task.run.headSha + ' · 判断 revision ' + (task.judgmentRevision || 0)));
    const actor = field(panel,'验收人 actorId（本地身份）','reviewer');
    for (const criterion of criteria) {
      const row = element('fieldset'); row.append(element('legend',criterion.id + ' · ' + criterion.statement));
      const current = task.humanReview?.entries?.filter(e => e.criterionId === criterion.id && e.specDigest === task.spec.digest && e.runId === task.run.runId && e.headSha === task.run.headSha).slice(-1)[0];
      row.append(element('p','当前人工记录：' + (current ? current.decision + ' · ' + current.reason : '尚未验收')));
      const decisionLabel = element('label','判断'), decision = element('select');
      for (const [value,label] of [['','请选择判断'],['PASS','PASS · 满足'],['FAIL','FAIL · 不满足'],['SKIPPED','SKIPPED · 暂不判断']]) {
        const option = element('option',label); option.value = value; decision.append(option);
      }
      decision.value = ''; decisionLabel.append(decision); row.append(decisionLabel);
      const reason = field(row,'验收理由（必填）'); reason.maxLength = 16000;
      const refs = [];
      const refsBox = element('details'); refsBox.append(element('summary','引用当前任务已有 Artifact（可选）'));
      for (const ref of artifacts.artifactRefs || []) {
        const label = element('label',ref.label), check = element('input'); check.type = 'checkbox';
        label.prepend(check); refsBox.append(label); refs.push({id:ref.id,check});
      }
      row.append(refsBox);
      const confirmation = element('label','我已核对本项与当前运行产物。'), check = element('input'); check.type = 'checkbox';
      confirmation.prepend(check); row.append(confirmation);
      const save = button(row,'保存 ' + criterion.id + ' 人工验收',() => guarded(async () => {
        if (!check.checked || snapshot !== task) return;
        if (!decision.value || !reason.value.trim() || !actor.value.trim()) { notice('请选择判断并填写验收理由和验收人。',true); return; }
        const body = {expectedVersion:task.version,expectedSpecDigest:task.spec.digest,expectedRunId:task.run.runId,
          expectedHeadSha:task.run.headSha,expectedJudgmentRevision:task.judgmentRevision || 0,criterionId:criterion.id,
          decision:decision.value,reason:reason.value.trim(),actorId:actor.value.trim(),artifactRefs:refs.filter(r => r.check.checked).map(r => r.id)};
        check.checked = false; save.disabled = true;
        try { await api('/' + task.changeId + '/human-evidence',body); await load(task.changeId); await list(); notice('人工验收已保存，交付判断已重算；请查看独立的交付审批与发布状态。'); }
        catch (error) { try { await load(task.changeId); await list(); } catch (_) { /* Failed reload removes controls. */ } throw error; }
      }));
      const allowed = ['DELIVERY_REVIEW','FAILED','PUBLISHING','COMPLETED'].includes(task.state);
      save.disabled = true;
      check.addEventListener('change',() => { save.disabled = !check.checked || busy || !allowed; });
      panel.append(row);
    }
    root.append(panel);
  }
  function draftControls(root, task) {
    if (!task.draftJob) return;
    const job = task.draftJob, panel = element('section', undefined, 'actions');
    const labels = {PENDING:'等待后台生成',RUNNING:'正在后台生成',RETRY_WAIT:'等待自动重试',SUCCEEDED:'草稿已生成',FAILED:'草稿生成失败',CANCELED:'草稿生成已取消'};
    panel.append(element('h3',labels[job.status] || job.status),element('p','目标 r' + job.revision + ' · 已领取 ' + job.attempts + '/3 次'));
    if (job.error) panel.append(element('p',job.error,job.status === 'FAILED'?'bad':'warn'));
    if (job.status === 'RETRY_WAIT') panel.append(element('p','下次尝试：' + new Date(job.availableAt).toLocaleString()));
    if (task.state === 'DRAFTING_SPEC' || (task.state === 'FAILED' && job.status === 'FAILED')) {
      const actor = field(panel,'操作者 actorId（本地身份）',task.requesterId);
      const cancel = task.state === 'DRAFTING_SPEC';
      button(panel,cancel?'取消草稿生成':'重试草稿生成',() => guarded(async () => {
        if (snapshot !== task || !actor.value.trim()) return;
        try {
          await api('/' + task.changeId + (cancel?'/draft-cancel':'/draft-retry'),{
            expectedVersion:task.version,expectedGeneration:job.generation,actorId:actor.value.trim()});
          notice(cancel?'取消已保存。':'重试已提交，正在后台生成。');
        } finally { await load(task.changeId); await list(); }
      }),cancel?'danger':'');
    }
    root.append(panel);
  }
  function awaitingCheck(task) {
    if (!task.run || !['DELIVERY_REVIEW','FAILED','PUBLISHING'].includes(task.state)) return false;
    const verdict = task.deliveryVerdict || task.run.verdict;
    if (verdict === 'PASSED' && task.state === 'DELIVERY_REVIEW' && !task.humanReview) return false;
    return !task.delivery || task.delivery.runId !== task.run.runId || task.delivery.judgmentRevision !== (task.judgmentRevision || 0);
  }
  function schedulePoll() {
    clearTimeout(pollTimer); pollTimer = null;
    if (!key || !selected || !polling || document.hidden || (snapshot && terminal.has(snapshot.state) && !awaitingCheck(snapshot))) return;
    pollTimer = setTimeout(() => guarded(async () => {
      try { await load(selected); await list(); }
      catch (error) { pollDelay = Math.min(10000, pollDelay * 2); if (error.status === 401) polling = false; throw error; }
    }), pollDelay);
  }
  function approval(root, task) {
    const spec = task.state === 'SPEC_REVIEW', panel = element('section',undefined,'actions');
    panel.append(element('h3',spec?'Spec 审批':'Delivery Approval'));
    panel.append(element('p', '本次确认绑定 version ' + task.version + ' · digest ' + task.spec.digest + (spec?'':' · run ' + task.run.runId + ' · head ' + task.run.headSha + ' · 判断 revision ' + (task.judgmentRevision || 0))));
    const actor = field(panel,'审批人 actorId（演示身份）','techlead');
    const reason = field(panel,spec?'审批理由 / 补充要求':'交付审批理由');
    const label = element('label','我已核对当前契约、diff、Evidence 和绑定身份。'); const check = element('input'); check.type = 'checkbox'; label.prepend(check); panel.append(label);
    const buttons = element('div',undefined,'buttons');
    for (const decision of spec ? ['APPROVE','SUPPLEMENT','REJECT']:['APPROVE','REJECT']) {
      const b = button(buttons, (spec?'Spec ':'Delivery ') + decision, () => guarded(async () => {
        if (!check.checked || snapshot !== task) return;
        if (!actor.value.trim() || (decision === 'SUPPLEMENT' && !reason.value.trim())) { notice('请填写审批人；补充决策必须填写补充要求。',true); return; }
        const body = {decision, actorId:actor.value.trim(),reason:reason.value,expectedVersion:task.version};
        if (spec) { body.expectedDraftDigest = task.spec.digest; if (decision === 'SUPPLEMENT') body.supplement = reason.value; }
        else { body.expectedSpecDigest = task.spec.digest; body.expectedHeadSha = task.run.headSha; body.expectedRunId = task.run.runId; body.expectedJudgmentRevision = task.judgmentRevision || 0; }
        check.checked = false; buttons.querySelectorAll('button').forEach(b => b.disabled = true);
        try { await api('/' + task.changeId + (spec?'/spec-decisions':'/delivery-decisions'),body); await load(task.changeId); await list(); notice('决策已保存；请查看当前阶段和事件。'); }
        catch (error) { try { await load(task.changeId); await list(); } catch (_) { /* load already disables approval */ } throw error; }
      }),decision === 'REJECT'?'danger':'');
      b.disabled = true;
      check.addEventListener('change',() => { b.disabled = !check.checked || busy || (!spec && decision === 'APPROVE' && ((task.deliveryVerdict || task.run?.verdict) !== 'PASSED' || task.run?.status !== 'FINISHED')); });
    }
    panel.append(buttons); root.append(panel);
  }
  async function guarded(action) { if (busy) return; if (!key) { notice('请先连接 Runtime API。',true); return; } busy = true; try { await action(); } catch (error) { showError(error); } finally { busy = false; schedulePoll(); } }
  function creationNotice(text, error = false) {
    const status = $('create-status');
    status.hidden = !text;
    status.textContent = text;
    status.dataset.error = String(error);
    notice(text, error);
  }
  function showCreationState(task) {
    if (task.state === 'FAILED') {
      creationNotice(task.draftJob && task.draftJob.status !== 'FAILED'
        ? '任务已保存，后续执行失败。请查看任务详情和事件。'
        : '任务已保存，但 Spec 草稿生成失败。请查看任务详情和事件。', true);
    } else if (task.state === 'SPEC_REVIEW') {
      creationNotice('Spec 草稿已生成，请查看任务详情并确认。');
    } else if (task.state === 'DRAFTING_SPEC') {
      creationNotice('任务已保存，Spec 草稿正在后台生成。可关闭页面，重新连接后继续查看。');
    } else {
      creationNotice('已找到任务，当前状态：' + task.state + '。请查看任务详情。');
    }
  }
  function setCreating(active) {
    const button = $('create-button');
    button.disabled = active;
    button.textContent = active ? '创建中…' : '创建任务';
    button.classList.toggle('loading', active);
    $('new-request').disabled = active;
    $('create').setAttribute('aria-busy', String(active));
  }
  function newRequest() {
    creation = null; creationId = null;
    $('idempotency').value = demo ? 'offline-refund-v1' : crypto.randomUUID();
    $('create-status').hidden = true;
    $('create-status').textContent = '';
  }
  $('connect').addEventListener('submit', async event => { event.preventDefault(); if (busy) return; key = $('key').value; $('key').value = ''; await guarded(async () => { const cap = await api('/capabilities'); demo = cap.offlineDemo; $('mode').textContent = demo?'离线模拟执行 · Mock SCM':'真实模型执行 · Mock SCM'; $('normal-fields').hidden = demo; $('demo-info').hidden = !demo; newRequest(); await list();
    const restored = location.hash.slice(1);
    if (/^change_[A-Za-z0-9_-]+$/.test(restored)) await load(restored);
    notice(demo?'已连接离线演示；Draft / ReAct 为确定性替身，无真实模型调用。':'已连接正常模式；创建或执行任务可能调用已配置模型。'); }); });
  $('disconnect').addEventListener('click',() => { if (busy) return; key = ''; selected = snapshot = creation = creationId = null; generation++; clearTimeout(pollTimer); $('list').replaceChildren(); $('detail').replaceChildren(); $('mode').textContent = '已断开'; notice('密钥已从本页内存清除。'); });
  $('refresh').addEventListener('click',() => guarded(async () => { await list(); if (selected) await load(selected); notice('已刷新，请重新确认审批内容。'); }));
  $('new-request').addEventListener('click',() => { if (!busy) { newRequest(); notice('已准备新请求；离线 fixture 仍按固定键幂等。'); } });
  $('create').addEventListener('submit',event => { event.preventDefault(); guarded(async () => {
    if (!creation) creation = demo?{fixture:'offline-refund.json'}:{idempotencyKey:$('idempotency').value,title:$('title').value,requirement:$('requirement').value,actorId:$('requester').value,repository:{path:$('repository').value,baseRef:$('base-ref').value}};
    setCreating(true);
    creationNotice('正在提交并保存任务，请稍候。Spec 草稿将在后台生成。');
    let saved = false;
    try {
      const task = await api('', creation);
      saved = true; creationId = task.changeId;
      creationNotice('任务已保存，正在加载 Spec 与详情…');
      await list();
      await load(task.changeId);
      showCreationState(snapshot || task);
    } catch (error) {
      if ([400,403,422].includes(error.status)) creation = null;
      const message = error.message || '连接失败，请检查本地服务。';
      creationNotice((saved ? '任务已保存，但详情加载失败。' : '创建请求未完成。') + message, true);
    } finally {
      setCreating(false);
    }
  }); });
  $('poll-toggle').addEventListener('click', () => {
    polling = !polling; $('poll-toggle').textContent = polling?'暂停自动更新':'继续自动更新'; schedulePoll();
  });
  document.addEventListener('visibilitychange', schedulePoll);
  window.addEventListener('pagehide', () => clearTimeout(pollTimer));
  newRequest();
})();

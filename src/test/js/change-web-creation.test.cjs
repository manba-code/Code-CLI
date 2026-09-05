// Run with: node --test src/test/js/change-web-creation.test.cjs
// Execute the shipped event handlers with a deliberately pending create response.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/paichange-web/app.js'), 'utf8');

class Element {
  constructor(tag) { this.tagName = tag; this.textContent = ''; this.value = ''; this.disabled = false; this.hidden = true; this.dataset = {}; this.children = []; this.events = {}; this.attributes = {}; this.classList = {toggle() {}}; }
  addEventListener(type, handler) { const prior = this.events[type]; this.events[type] = prior ? (...args) => { prior(...args); return handler(...args); } : handler; }
  append(...children) { this.children.push(...children); }
  prepend(...children) { this.children.unshift(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(key, value) { this.attributes[key] = value; }
  querySelectorAll(tag) { return this.children.flatMap(c => [...(c.tagName === tag ? [c] : []), ...c.querySelectorAll(tag)]); }
  set innerHTML(value) { throw new Error('Untrusted HTML rendering forbidden'); }
}
const tick = () => new Promise(resolve => setImmediate(resolve));
async function fixture(options = {}) {
  const elements = new Map();
  const el = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  const requests = [], timers = new Map(); let timerId = 0;
  const location = {hash:options.hash || ""}, documentEvents = {}, windowEvents = {};
  let finishCreate, posts = 0, task = {changeId:'change_test',version:2,state:'SPEC_REVIEW',title:'Test',requirement:'Test',spec:{revision:1,digest:'digest'}};
  if (options.task) Object.assign(task, options.task);
  const reply = (value, status = 200) => ({ok:status < 400,status,json:async () => value});
  vm.runInNewContext(source, {
    document:{getElementById:el,createElement:tag => new Element(tag),addEventListener(name, handler) { documentEvents[name] = handler; }},
    window:{addEventListener(name, handler) { windowEvents[name] = handler; }},location,
    setTimeout(fn, ms) { const id = ++timerId; timers.set(id,{fn,ms}); return id; },clearTimeout(id) { timers.delete(id); },
    crypto:{randomUUID:() => 'same-key'},setInterval() {},
    fetch:async (url, requestOptions) => {
      if (requestOptions.method === 'POST') { posts++; requests.push({url,body:requestOptions.body}); return new Promise(resolve => { finishCreate = resolve; }); }
      if (url.endsWith('/capabilities')) return reply({offlineDemo:true});
      if (url.endsWith('/artifacts')) return reply({version:task.version,revisions:[],criteria:[],verifiers:[],criterionResults:[],verificationAttempts:[],evidence:[],...options.artifacts});
      if (url.endsWith('/events')) return reply({events:[]});
      return reply(url === '/v1/changes' ? {changes:[task]} : task);
    }
  });
  el('key').value = 'test-only';
  await el('connect').events.submit({preventDefault() {}});
  return {el,timers,location,windowEvents,setTask:update => Object.assign(task,update),tick,submit:() => el('create').events.submit({preventDefault() {}}),posts:() => posts, requests,
    finish:async (status = 201, state = 'SPEC_REVIEW') => { task.state = state; finishCreate(reply(task,status)); await tick(); await tick(); }};
}

test('pending creation immediately shows feedback, blocks duplicates and recovers after success', async () => {
  const f = await fixture();
  f.submit();
  assert.match(f.el('notice').textContent, /正在.*Spec/);
  assert.match(f.el('create-status').textContent, /正在.*Spec/);
  assert.equal(f.el('create-status').hidden, false);
  assert.equal(f.el('create-button').disabled, true);
  assert.match(f.el('create-button').textContent, /创建中/);
  f.submit(); assert.equal(f.posts(), 1);
  await f.finish();
  assert.equal(f.el('create-button').disabled, false);
  assert.equal(f.el('new-request').disabled, false);
  assert.match(f.el('create-status').textContent, /Spec 草稿已生成/);
});

test('HTTP failure restores buttons and retry retains the same creation request', async () => {
  const f = await fixture(); f.submit(); await f.finish(500);
  assert.equal(f.el('create-button').disabled, false);
  assert.equal(f.el('create-status').dataset.error, 'true');
  assert.match(f.el('create-status').textContent, /失败/);
  f.submit(); assert.equal(f.posts(), 2); assert.deepEqual(f.requests[0], f.requests[1]); await f.finish();
});

test('persisted Draft failure is reported as failure instead of a ready Spec', async () => {
  const f = await fixture(); f.submit(); await f.finish(201, 'FAILED');
  assert.match(f.el('create-status').textContent, /草稿生成失败/);
  assert.equal(f.el('create-status').dataset.error, 'true');
  assert.equal(f.el('create-button').disabled, false);
});

const allText = element => element.textContent + ' ' + element.children.map(allText).join(' ');
const findButton = (element, text) => element.textContent === text ? element : element.children.map(child => findButton(child,text)).find(Boolean);

test('201 DRAFTING releases submit controls and polling backs off, stops and resumes', async () => {
  const f = await fixture(); f.submit(); await f.finish(201, 'DRAFTING_SPEC');
  assert.match(f.el('create-status').textContent, /后台生成/);
  assert.equal(f.el('create-button').disabled, false);
  assert.equal(f.timers.size, 1);
  const first = [...f.timers.values()][0];
  await first.fn(); await tick();
  assert.equal([...f.timers.values()][0].ms, 2000);
  f.el('poll-toggle').events.click(); assert.equal(f.timers.size, 0);
  f.el('poll-toggle').events.click(); assert.equal(f.timers.size, 1);
  f.setTask({state:'SPEC_REVIEW'});
  await [...f.timers.values()][0].fn(); await tick();
  assert.equal(f.timers.size, 0);
  assert.match(f.el('create-status').textContent, /草稿已生成/);
});

test('reconnect restores selected task from hash and disconnect stops tracking', async () => {
  const f = await fixture({hash:'#change_test',task:{state:'DRAFTING_SPEC',draftJob:{status:'RUNNING',generation:'g1',attempts:1,revision:1}}});
  assert.match(allText(f.el('detail')), /正在后台生成/);
  assert.equal(f.timers.size, 1);
  assert.ok(findButton(f.el('detail'),'取消草稿生成'));
  f.el('disconnect').events.click();
  assert.equal(f.timers.size, 0);
  assert.equal(f.el('detail').children.length, 0);
});

test('failure shows safe text and retry binds version/generation; conflict refreshes without replay', async () => {
  const f = await fixture({hash:'#change_test',task:{state:'FAILED',requesterId:'owner',draftJob:{status:'FAILED',generation:'g1',attempts:3,revision:1,error:'<img src=x onerror=alert(1)>'}}});
  assert.match(allText(f.el('detail')), /草稿生成失败/);
  assert.match(allText(f.el('detail')), /<img src=x onerror=alert/);
  const retry = findButton(f.el('detail'),'重试草稿生成');
  const pending = retry.events.click();
  assert.equal(f.posts(),1);
  assert.equal(f.requests[0].url,'/v1/changes/change_test/draft-retry');
  assert.deepEqual(JSON.parse(f.requests[0].body),{expectedVersion:2,expectedGeneration:'g1',actorId:'owner'});
  await f.finish(409, 'SPEC_REVIEW'); await pending;
  assert.match(f.el('notice').textContent,/重新核对/);
  assert.equal(f.posts(),1);
  assert.equal(findButton(f.el('detail'),'重试草稿生成'),undefined);
});

const humanTask = {state:'DELIVERY_REVIEW',run:{runId:'run-1',headSha:'head-1',status:'FINISHED',verdict:'NEEDS_HUMAN'},
  deliveryVerdict:'NEEDS_HUMAN',judgmentRevision:0,route:{deliveryApprovalRequired:true},risk:{level:'HIGH'}};
const humanArtifacts = {criteria:[{id:'AC-H1',statement:'Review <img src=x onerror=alert(1)>',oracle:{type:'HUMAN'}}],
  artifactRefs:[{id:'code-diff',label:'引用代码 diff'}]};
const byText = (root, text) => root.textContent === text ? root : root.children.map(c => byText(c,text)).find(Boolean);
function fillHuman(f) {
  const panel = byText(f.el('detail'),'人工验收 · Human Evidence'); assert.ok(panel);
  const reason = byText(f.el('detail'),'验收理由（必填）').children[0]; reason.value = '<script>alert(1)</script> observed';
  byText(f.el('detail'),'判断').children[0].value = 'PASS';
  const check = byText(f.el('detail'),'我已核对本项与当前运行产物。').children[0]; check.checked = true; check.events.change();
  const ref = byText(f.el('detail'),'引用代码 diff').children[0]; ref.checked = true;
  return findButton(f.el('detail'),'保存 AC-H1 人工验收');
}
test('human evidence submits exact identity and artifact IDs; duplicate clicks are blocked', async () => {
  const f = await fixture({hash:'#change_test',task:humanTask,artifacts:humanArtifacts});
  const save = fillHuman(f); const pending = save.events.click();
  assert.equal(save.disabled,true); save.events.click(); assert.equal(f.posts(),1);
  const body = JSON.parse(f.requests[0].body);
  assert.deepEqual(body,{expectedVersion:2,expectedSpecDigest:'digest',expectedRunId:'run-1',expectedHeadSha:'head-1',
    expectedJudgmentRevision:0,criterionId:'AC-H1',decision:'PASS',reason:'<script>alert(1)</script> observed',actorId:'reviewer',artifactRefs:['code-diff']});
  assert.equal(f.requests[0].url,'/v1/changes/change_test/human-evidence');
  f.setTask({version:3,judgmentRevision:1}); await f.finish(200,'DELIVERY_REVIEW'); await pending;
  assert.match(f.el('notice').textContent,/人工验收已保存/);
  assert.equal(findButton(f.el('detail'),'保存 AC-H1 人工验收').disabled,true);
  assert.ok(f.timers.size); // New judgment awaits the separate Check publication.
});
test('stale human submission refreshes and requires a new confirmation without replay', async () => {
  const f = await fixture({hash:'#change_test',task:humanTask,artifacts:humanArtifacts});
  const pending = fillHuman(f).events.click();
  f.setTask({version:9,judgmentRevision:4}); await f.finish(409,'DELIVERY_REVIEW'); await pending;
  assert.equal(f.posts(),1); assert.match(f.el('notice').textContent,/重新核对/);
  assert.equal(byText(f.el('detail'),'我已核对本项与当前运行产物。').children[0].checked || false,false);
  assert.equal(findButton(f.el('detail'),'保存 AC-H1 人工验收').disabled,true);
  assert.match(allText(f.el('detail')),/判断 revision 4/);
});
test('missing or skipped human evidence cannot enable Delivery Approval', async () => {
  const f = await fixture({hash:'#change_test',task:humanTask,artifacts:humanArtifacts});
  const check = byText(f.el('detail'),'我已核对当前契约、diff、Evidence 和绑定身份。').children[0];
  check.checked = true; check.events.change();
  assert.equal(findButton(f.el('detail'),'Delivery APPROVE').disabled,true);
  assert.match(allText(f.el('detail')),/不能代替验收/);
});
test('effective PASSED allows separate HIGH approval and binds judgment revision despite original NEEDS_HUMAN', async () => {
  const f = await fixture({hash:'#change_test',task:{...humanTask,deliveryVerdict:'PASSED',judgmentRevision:2},artifacts:humanArtifacts});
  const check = byText(f.el('detail'),'我已核对当前契约、diff、Evidence 和绑定身份。').children[0];
  check.checked = true; check.events.change();
  const approve = findButton(f.el('detail'),'Delivery APPROVE'); assert.equal(approve.disabled,false);
  const pending = approve.events.click();
  const body = JSON.parse(f.requests[0].body); assert.equal(body.expectedRunId,'run-1'); assert.equal(body.expectedJudgmentRevision,2);
  assert.equal(f.requests[0].url,'/v1/changes/change_test/delivery-decisions');
  f.setTask({state:'COMPLETED',deliveryApprovalValid:true,deliveryApproval:{decision:'APPROVED'},delivery:{conclusion:'success'}});
  await f.finish(200,'COMPLETED'); await pending; assert.match(allText(f.el('detail')),/发布完成/);
});
test('human text renders literally and a publishing failure is never shown as completed', async () => {
  const f = await fixture({hash:'#change_test',task:{...humanTask,state:'PUBLISHING',deliveryVerdict:'PASSED',humanReview:{entries:[],judgments:[]}},artifacts:humanArtifacts});
  assert.match(allText(f.el('detail')),/<img src=x onerror=alert/);
  assert.match(allText(f.el('detail')),/等待发布/); assert.doesNotMatch(allText(f.el('detail')),/发布完成/);
  assert.equal(fillHuman(f).disabled,false);
});

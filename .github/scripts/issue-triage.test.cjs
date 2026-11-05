const { test } = require('node:test');
const assert = require('node:assert/strict');
const { validateReport, buildComment, triageIssue, COMMENT_MARKER } = require('./issue-triage.cjs');

const attachment = '[LSPosed.log](https://github.com/user-attachments/files/12345/LSPosed.log)';
const details = '打开控制中心后点击音乐卡片，返回桌面再次打开时出现界面闪烁，连续操作三次均可复现。预期音乐封面正常显示，实际显示为空白；关闭其他模块后仍然发生，重启应用也无法恢复。';
const validBody = `### 设备型号\nXiaomi 14\n### HyperOS 具体版本\nOS2.0.2.0.VNCCNXM\n### LSPosed 框架版本与日志\nLSPosed 1.10.0\n日志：java.lang.IllegalStateException\n### 问题描述\n${details}\n### 上传日志或相关文件\n${attachment}`;

function mockRun({ body = validBody, state = 'open', comments = [], labelExists = true, labelRace = false, pullRequest = false } = {}) {
  const calls = [];
  let labelReads = 0;
  const issues = {
    get: async (args) => {
      calls.push(['get', args]);
      return { data: { number: 42, state, body, ...(pullRequest ? { pull_request: {} } : {}) } };
    },
    getLabel: async (args) => {
      calls.push(['getLabel', args]);
      if (!labelExists && labelReads++ === 0) throw Object.assign(new Error('not found'), { status: 404 });
      return { data: { name: 'invalid' } };
    },
    createLabel: async (args) => {
      calls.push(['createLabel', args]);
      if (labelRace) throw Object.assign(new Error('already exists'), { status: 422 });
    },
    addLabels: async (args) => { calls.push(['addLabels', args]); },
    listComments: () => {},
    createComment: async (args) => { calls.push(['createComment', args]); },
    update: async (args) => { calls.push(['update', args]); },
  };
  return {
    calls,
    github: {
      rest: { issues },
      paginate: async (_method, args) => { calls.push(['listComments', args]); return comments; },
    },
    // Deliberately stale payload: the implementation must fetch the current body.
    context: { repo: { owner: 'ColdP', repo: 'HyperChanger' }, payload: { issue: { number: 42, body: 'stale payload' } } },
    core: { info() {} },
  };
}

test('complete Chinese, English and Japanese reports pass', () => {
  assert.deepEqual(validateReport(validBody), []);
  for (const keyword of ['LSPosed', 'logcat', 'logs', 'ログ', 'Exception']) {
    assert.deepEqual(validateReport(`${details}\n${keyword}\n${attachment}`), []);
  }
});

test('short body, missing keyword and missing attachment fail independently', () => {
  assert.equal(validateReport(`日志\n${attachment}`).length, 1);
  assert.equal(validateReport(`${details}\n${attachment}`).length, 1);
  assert.equal(validateReport(`${details}\n日志`).length, 1);
  assert.equal(validateReport(null).length, 3);
});

test('body threshold rejects 59 characters and accepts exactly 60', () => {
  assert.equal(validateReport(`日志${'字'.repeat(57)}\n${attachment}`).length, 1);
  assert.deepEqual(validateReport(`日志${'字'.repeat(58)}\n${attachment}`), []);
});

test('form headings, comments, links and placeholders do not satisfy body requirements', () => {
  const body = `### LSPosed 框架版本与日志\n_No response_\n<!-- 日志 ${details} -->\n[日志 ${details}](https://example.com/log.txt)\n${attachment}`;
  assert.equal(validateReport(body).length, 2);
});

test('accept current and legacy GitHub file attachments with supported extensions', () => {
  for (const url of [
    'https://github.com/user-attachments/files/12345/log.txt',
    'https://github.com/user-attachments/files/12345/LSPosed.ZIP',
    'https://github.com/ColdP/HyperChanger/files/12345/log.log',
  ]) {
    assert.deepEqual(validateReport(`${details}\n日志\n[下载文件](${url})`), []);
  }
});

test('accept GitHub screenshot attachments', () => {
  for (const screenshot of [
    'https://github.com/user-attachments/assets/01234567-89ab-cdef-0123-456789abcdef',
    'https://github.com/ColdP/HyperChanger/files/12345/screenshot.PNG',
    ...['png', 'jpg', 'jpeg', 'gif', 'webp'].map((ext) => `https://github.com/user-attachments/files/12345/screenshot.${ext}`),
  ]) {
    assert.deepEqual(validateReport(`${details}\n日志\n![截图](${screenshot})`), []);
  }
});

test('reject external URLs, hidden attachments and pasted code links', () => {
  for (const fake of [
    'https://example.com/log.txt',
    'https://github.com.evil.example/user-attachments/files/12345/log.txt',
    'https://github.com/user-attachments/files/12345/document.pdf',
    `<!-- ${attachment} -->`,
    `\`\`\`text\n${attachment}\n\`\`\``,
  ]) {
    assert.equal(validateReport(`${details}\n日志\n${fake}`).length, 1, fake);
  }
});

test('notification contains real newlines, all reasons and literal extension formatting', () => {
  const comment = buildComment(['第一项', '第二项']);
  assert.ok(comment.includes('\n- 第一项\n- 第二项\n'));
  assert.ok(comment.includes('`.log`、`.txt`、`.zip` 或截图'));
  assert.ok(comment.startsWith(COMMENT_MARKER));
});

test('valid latest body causes no moderation writes', async () => {
  const run = mockRun();
  await triageIssue(run);
  assert.deepEqual(run.calls.map(([method]) => method), ['get']);
});

test('invalid body is labeled, notified and closed exactly once', async () => {
  const run = mockRun({ body: '' });
  await triageIssue(run);
  assert.deepEqual(run.calls.map(([method]) => method), ['get', 'getLabel', 'addLabels', 'listComments', 'createComment', 'update']);
  assert.deepEqual(run.calls.find(([method]) => method === 'addLabels')[1].labels, ['invalid']);
  const close = run.calls.find(([method]) => method === 'update')[1];
  assert.equal(close.state, 'closed');
  assert.equal(close.state_reason, 'not_planned');
  assert.equal(close.issue_number, 42);
});

test('missing invalid label is created automatically', async () => {
  const run = mockRun({ body: '', labelExists: false });
  await triageIssue(run);
  assert.equal(run.calls.filter(([method]) => method === 'createLabel').length, 1);
  assert.equal(run.calls.at(-1)[0], 'update');
});

test('concurrent label creation does not block closing', async () => {
  const run = mockRun({ body: '', labelExists: false, labelRace: true });
  await triageIssue(run);
  assert.equal(run.calls.filter(([method]) => method === 'getLabel').length, 2);
  assert.equal(run.calls.at(-1)[0], 'update');
});

test('reopened invalid report is closed without repeating the bot comment', async () => {
  const run = mockRun({ body: '', comments: [{ user: { type: 'Bot', login: 'github-actions[bot]' }, body: COMMENT_MARKER }] });
  await triageIssue(run);
  assert.equal(run.calls.filter(([method]) => method === 'createComment').length, 0);
  assert.equal(run.calls.at(-1)[0], 'update');
});

test('a user cannot suppress the bot comment by copying its marker', async () => {
  const run = mockRun({ body: '', comments: [{ user: { type: 'User', login: 'reporter' }, body: COMMENT_MARKER }] });
  await triageIssue(run);
  assert.equal(run.calls.filter(([method]) => method === 'createComment').length, 1);
});

test('already closed issues and pull requests are untouched', async () => {
  for (const options of [{ state: 'closed', body: '' }, { pullRequest: true, body: '' }]) {
    const run = mockRun(options);
    await triageIssue(run);
    assert.deepEqual(run.calls.map(([method]) => method), ['get']);
  }
});

test('API failures fail visibly instead of reporting successful moderation', async () => {
  const run = mockRun({ body: '' });
  run.github.rest.issues.addLabels = async () => { throw new Error('permission denied'); };
  await assert.rejects(triageIssue(run), /permission denied/);
  assert.equal(run.calls.filter(([method]) => method === 'update').length, 0);
});

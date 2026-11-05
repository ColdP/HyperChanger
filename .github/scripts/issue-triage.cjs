const MIN_BODY_LENGTH = 60;
const COMMENT_MARKER = '<!-- hyperchanger-invalid-report -->';

function validateReport(body = '') {
  const content = (body || '').replace(/<!--.*?-->/gs, '');
  // GitHub-hosted log files and image attachments count; external links do not.
  const attachmentPattern = /https:\/\/github\.com\/(?:user-attachments\/assets\/[0-9a-f-]+|(?:user-attachments\/files|[^/\s]+\/[^/\s]+\/files)\/\d+\/[^\s<>"`)]*\.(?:log|txt|zip|png|jpe?g|gif|webp))(?=$|[\s<>"`)\]?#])/giu;
  const hasAttachment = attachmentPattern.test(content.replace(/```[^\n]*\n[\s\S]*?```/g, ''));
  const userText = content
    .replace(/^#{1,6}\s+.*$/gm, '')
    .replace(/!?\[([^\]]*)\]\(https?:\/\/[^\s)]+\)/gi, '')
    .replace(/https?:\/\/[^\s<>]+/gi, '')
    .replace(/^\s*`{3,}[^\n]*$/gm, '')
    .replace(/_No response_/gi, '')
    .replace(/[`*_>#]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const hasLogKeyword = /(?:\blsposed\b|\blogcat\b|日志|ログ|\blog(?:s)?\b|fatal exception|androidruntime|xposedbridge|java\.lang\.|\bexception\b|stack\s*trace|\b[VDIWEF]\/\w+)/iu.test(userText);
  const reasons = [];
  if ([...userText].length < MIN_BODY_LENGTH) {
    reasons.push(`正文有效内容不足 ${MIN_BODY_LENGTH} 个字符（不计算表单标题、附件链接及空白占位内容）。`);
  }
  if (!hasLogKeyword) {
    reasons.push('正文没有日志关键词（如 LSPosed、logcat、日志、ログ、Exception），请填写框架版本及相关日志说明。');
  }
  if (!hasAttachment) {
    reasons.push('缺少 GitHub 上传的 .log、.txt、.zip 或截图附件；外部链接和代码块中的链接不计入。');
  }
  return reasons;
}

function buildComment(reasons) {
  return [
    COMMENT_MARKER,
    '',
    '此报告未通过自动检查，将被标记为 `invalid` 并自动关闭：',
    '',
    ...reasons.map((reason) => `- ${reason}`),
    '',
    '请使用 Bug 报告表单重新提交，填写设备型号、完整 HyperOS 版本、LSPosed 框架版本与日志，并上传相关的 `.log`、`.txt`、`.zip` 或截图文件。',
    '正文有效内容需至少 60 个字符。上传前请移除日志中的个人隐私信息。',
    '',
    'This report is incomplete and will be labeled `invalid` and closed automatically. Please submit a complete report with your device model, full HyperOS version, LSPosed version and logs, at least 60 characters of actual text, and an uploaded `.log`, `.txt`, `.zip` or screenshot file.',
    '',
    'この報告は情報が不足しているため、`invalid` ラベルを付けて自動的に終了します。端末名、HyperOS の完全なバージョン、LSPosed のバージョンとログ、60 文字以上の本文、アップロードした `.log`・`.txt`・`.zip` またはスクリーンショットを添えて再投稿してください。',
  ].join('\n');
}

async function ensureInvalidLabel(github, repo) {
  try {
    await github.rest.issues.getLabel({ ...repo, name: 'invalid' });
  } catch (error) {
    if (error.status !== 404) throw error;
    try {
      await github.rest.issues.createLabel({
        ...repo,
        name: 'invalid',
        color: 'e4e669',
        description: 'Incomplete issue report / 问题报告信息不完整 / 報告の情報不足',
      });
    } catch (creationError) {
      // Another Issue run may have created the repository-wide label concurrently.
      if (creationError.status !== 422) throw creationError;
      await github.rest.issues.getLabel({ ...repo, name: 'invalid' });
    }
  }
}

async function triageIssue({ github, context, core }) {
  const repo = context.repo;
  const target = { ...repo, issue_number: context.payload.issue.number };
  // Edited/reopened events may queue; always validate the current body and state.
  const { data: issue } = await github.rest.issues.get(target);
  if (issue.pull_request || issue.state !== 'open') return;
  const reasons = validateReport(issue.body);
  if (reasons.length === 0) {
    core.info(`Issue #${issue.number} passed validation.`);
    return;
  }

  await ensureInvalidLabel(github, repo);
  await github.rest.issues.addLabels({ ...target, labels: ['invalid'] });
  const comments = await github.paginate(github.rest.issues.listComments, {
    ...target,
    per_page: 100,
  });
  const notified = comments.some((comment) =>
    comment.user?.type === 'Bot'
    && /^github-actions(?:\[bot\])?$/.test(comment.user?.login || '')
    && comment.body?.includes(COMMENT_MARKER));
  if (!notified) {
    await github.rest.issues.createComment({ ...target, body: buildComment(reasons) });
  }
  await github.rest.issues.update({ ...target, state: 'closed', state_reason: 'not_planned' });
  core.info(`Closed Issue #${issue.number}: ${reasons.join(' ')}`);
}

module.exports = { validateReport, buildComment, triageIssue, COMMENT_MARKER };

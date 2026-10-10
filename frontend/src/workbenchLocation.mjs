export function workbenchLocation(search, storage) {
  const query = new URLSearchParams(search);
  const workspace = query.get('workspace');
  const project = query.get('project');
  if (workspace && project) return {workspace, project};
  return {workspace: storage.getItem('wa2-d2-workspace') || '', project: storage.getItem('wa2-d2-project') || ''};
}

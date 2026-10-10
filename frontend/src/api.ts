import type { Workbench, Project, PlanPayload, Proposal, JsonDoc } from './types';
import {createCommandTransport,ApiError} from './commandTransport.mjs';
export {ApiError};
type PendingCommand={path:string;method:string;body:unknown;workspaceId:string;projectId:string};
const transport=createCommandTransport({storage:localStorage,fetchFn:window.fetch.bind(window),timeoutSignal:()=>AbortSignal.timeout(20_000)});
export function pendingCommand():PendingCommand|null {return transport.pending();}
export function clearPendingCommand(){transport.clear();}
export function retryPendingCommand(){return transport.retryPending();}
async function request<T>(path:string,method='GET',body?:unknown):Promise<T> {
  return await transport.request(path,method,body) as T;
}
export const actionKey=()=>crypto.randomUUID();
export async function confirmationRequest(path:string,method='GET',body?:unknown){
 const response=await fetch('/api/writing/v2'+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
 const result=await response.json();if(!response.ok)throw new ApiError(response.status,result.code||'REQUEST_FAILED');return result;
}
export const api={
  lookup:(p:PendingCommand)=>request<{actionStatus:string;outcomeCode:string;errorCode:string|null}>(`/actions/lookup?workspaceId=${encodeURIComponent(p.workspaceId)}&scopeKey=${encodeURIComponent('project:'+p.projectId)}&clientActionId=${encodeURIComponent((p.body as {clientActionId:string}).clientActionId)}`),
  workspace:()=>request<{workspaceId:string}>('/workspaces','POST',{displayName:'我的写作空间'}),
  projects:(workspace:string)=>request<{items:Project[]}>(`/workspaces/${workspace}/projects`),
  create:(workspaceId:string,title:string,researchGoal:string)=>request<Project>('/projects','POST',{workspaceId,title,researchGoal}),
  workbench:(project:string)=>request<Workbench>(`/projects/${project}/workbench`),
  savePlan:(w:Workbench,payload:PlanPayload,key:string)=>request(`/projects/${w.project.projectId}/plans`,'POST',{clientActionId:key,expectedProjectVersion:w.project.projectVersion,expectedPlanRevisionId:w.project.currentPlanRevisionId,payload}),
  saveChapter:(w:Workbench,chapterKey:string,expectedChapterRevisionId:string|null,contentJson:JsonDoc,key:string)=>request(`/projects/${w.project.projectId}/chapters/${chapterKey}`,'PUT',{clientActionId:key,expectedPlanRevisionId:w.article!.planRevisionId,expectedChapterRevisionId,expectedArticleVersion:w.article!.articleVersion,contentJson}),
  metadata:(w:Workbench,payload:Article['metadata'],key:string)=>request(`/projects/${w.project.projectId}/article/metadata`,'PUT',{clientActionId:key,expectedArticleVersion:w.article!.articleVersion,expectedMetadataRevisionId:w.article!.currentMetadataRevisionId,payload}),
  prepare:(w:Workbench,type:Proposal['type'])=>request<Proposal>(`/projects/${w.project.projectId}/proposals`,'POST',{clientActionId:actionKey(),type,origin:'HTTP',targetPlanRevisionId:type==='CONFIRM_PLAN'?w.plan!.planRevisionId:undefined,expectedProjectVersion:w.project.projectVersion,expectedArticleVersion:w.article?.articleVersion}),
  decide:(id:string,key:string)=>request(`/proposals/${id}/decision`,'POST',{clientActionId:key,decision:'APPROVE'})
};
import type { Article } from './types';

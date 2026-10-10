import { createRoot } from 'react-dom/client';
import { useEffect,useRef,useState } from 'react';
import { api,actionKey,ApiError,pendingCommand,clearPendingCommand,retryPendingCommand,confirmationRequest } from './api';
import {ConfirmationCard} from './ConfirmationCard.mjs';
import {createConfirmationDecision} from './confirmationDecision.mjs';
import { ChapterEditor } from './ChapterEditor';
import { StageAiPanel } from './StageAiPanel';
import {WorkbenchRightPanel,type RightMode} from './WorkbenchRightPanel';
import { BatchRewritePanel } from './BatchRewritePanel';
import {workbenchLocation} from './workbenchLocation.mjs';
import type { JsonDoc,PlanPayload,Project,Proposal,Workbench } from './types';
import './style.css';
import './workbenchA.css';

const blankPlan=():PlanPayload=>({researchQuestion:'',goals:'',chapters:[{title:'第一章',brief:''},{title:'第二章',brief:''}]});
const emptyDoc:JsonDoc={type:'doc',content:[{type:'paragraph'}]};
const confirmationDecisions=createConfirmationDecision({storage:localStorage,request:confirmationRequest});
const readable:Record<string,string>={VERSION_CONFLICT:'稿件已被更新。你的内容已保留，请对照最新版本后再保存。',ARTICLE_COMPLETE:'文章已完成，请先重新打开。',ARTICLE_INCOMPLETE:'还有空白或缺失章节，请先补齐正文。',STALE_CONFIRMATION:'内容已变化，请重新发起确认。',PROPOSAL_EXPIRED:'确认卡已过期，请重新发起。',PLAN_NOT_CONFIRMED:'请先确认计划。',OUTLINE_STRUCTURE_LOCKED:'已有正文后不能增删或重排章节，但可以改标题与说明。',EXPECTED_VERSION_REQUIRED:'缺少版本信息，请重新加载。'};

function App(){
  const [initialLocation]=useState(()=>workbenchLocation(location.search,localStorage));
  const [workspace,setWorkspace]=useState(initialLocation.workspace);
  const [projects,setProjects]=useState<Project[]>([]);
  const [w,setW]=useState<Workbench|null>(null);
  const [stage,setStage]=useState<'home'|'plan'|'body'|'article'>('home');
  const [plan,setPlan]=useState<PlanPayload>(blankPlan);
  const [chapterKey,setChapterKey]=useState('');
  const [selectedChapters,setSelectedChapters]=useState<string[]>([]);
  const [rightMode,setRightMode]=useState<RightMode>('CHAT');const [taskId,setTaskId]=useState('');
  const assistantOpen=rightMode!=='CLOSED';
  const showTask=(id:string)=>{setTaskId(id);setRightMode('TASK_DETAIL');};
  useEffect(()=>{setSelectedChapters([]);},[w?.project.projectId]);
  const [doc,setDoc]=useState<JsonDoc>(emptyDoc);
  const [editorEpoch,setEditorEpoch]=useState(0);
  const [abstract,setAbstract]=useState('');const [keywords,setKeywords]=useState('');
  const [dirty,setDirtyState]=useState(false);const [busy,setBusy]=useState(false);
  const dirtyRef=useRef(dirty);dirtyRef.current=dirty;
  const currentProjectRef=useRef(w?.project.projectId);currentProjectRef.current=w?.project.projectId;
  const editSerial=useRef(0);
  const setDirty=(value:boolean)=>{if(value)editSerial.current++;setDirtyState(value);};
  const [error,setError]=useState('');const [notice,setNotice]=useState('');
  const [newProject,setNewProject]=useState(false);const [title,setTitle]=useState('');const [goal,setGoal]=useState('');
  const [proposal,setProposal]=useState<Proposal|null>(null);
  const [proposalUnavailable,setProposalUnavailable]=useState(false);
  useEffect(()=>{if(!proposal)return;let closed=false;const id=proposal.proposalId;const check=async()=>{try{const value=await confirmationRequest(`/proposals/${id}`);if(!closed){setProposal(value);setProposalUnavailable(false);}}catch{if(!closed)setProposalUnavailable(true);}};void check();const timer=setInterval(()=>void check(),5000);return()=>{closed=true;clearInterval(timer);};},[proposal?.proposalId]);
  const [pendingNavigation,setPendingNavigation]=useState<(()=>void)|null>(null);
  const [conflict,setConflict]=useState<Workbench|null>(null);
  const [retryAllowed,setRetryAllowed]=useState(false);
  const complete=w?.article?.status==='COMPLETE';
  const chapter=w?.article?.chapters.find(c=>c.chapterKey===chapterKey);
  const report=(e:unknown)=>setError(e instanceof ApiError?(readable[e.code]||e.code):'网络请求未获得确定结果。保留当前内容，先查询结果，不要重复生成。');
  async function list(id=workspace){setProjects((await api.projects(id)).items);}
  function hydrate(value:Workbench,preferred=chapterKey){
    setW(value);setPlan(value.plan?.payload||blankPlan());setAbstract(value.article?.metadata.abstract||'');setKeywords((value.article?.metadata.keywords||[]).join('、'));
    const c=value.article?.chapters.find(c=>c.chapterKey===preferred)||value.article?.chapters[0];setChapterKey(c?.chapterKey||'');setDoc(c?.contentJson||emptyDoc);setEditorEpoch(n=>n+1);setDirty(false);setConflict(null);
  }
  async function open(id:string){const value=await api.workbench(id);hydrate(value,'');localStorage.setItem('wa2-d2-project',id);setStage(value.plan?'body':'plan');const pending=confirmationDecisions.pendingForProject(id);if(pending)setProposal(pending);}
  async function refreshAi(id:string){
    if(!id)return;const serial=editSerial.current;const value=await api.workbench(id);
    if(currentProjectRef.current&&currentProjectRef.current!==id){await list();return;}
    if(dirtyRef.current||serial!==editSerial.current){setConflict(value);setNotice('AI 已保存新稿；当前未保存修改保留，请对照。');}
    else {hydrate(value);if(!currentProjectRef.current){localStorage.setItem('wa2-d2-project',id);setStage('plan');}}
    await list();
  }
  useEffect(()=>{if(!workspace)return;void (async()=>{try{const entries=(await api.projects(workspace)).items;setProjects(entries);const id=workspace===initialLocation.workspace?initialLocation.project:localStorage.getItem('wa2-d2-project');if(id){if(!entries.some(p=>p.projectId===id)){setError('继续链接中的文章不属于当前空间，请核对链接。');return;}await open(id);}localStorage.setItem('wa2-d2-workspace',workspace);}catch(e){report(e);}})();},[workspace]);
  useEffect(()=>{const warn=(e:BeforeUnloadEvent)=>{if(dirty){e.preventDefault();e.returnValue='';}};window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn);},[dirty]);
  async function resolvePending(){
    const pending=pendingCommand();if(!pending)return;
    try{
      const result=await api.lookup(pending);clearPendingCommand();
      if(result.actionStatus==='SUCCEEDED'){setConflict(await api.workbench(pending.projectId));setNotice('上次操作已保存。当前编辑内容保留，请对照服务器版本再决定是否加载。');}
      else setError('上次操作已确定失败：'+(result.errorCode||result.outcomeCode));
    }catch(e){
      if(e instanceof ApiError&&e.status===404&&e.code==='ACTION_NOT_FOUND'){setRetryAllowed(true);setError('尚未查到已受理记录。可继续查询，或明确使用原编号重发手动保存；不会另建操作编号。');return;}
      report(e);
    }
  }
  async function retryOriginal(){
    const pending=pendingCommand();if(!pending)return;
    await retryPendingCommand();setRetryAllowed(false);setConflict(await api.workbench(pending.projectId));
    setNotice('原编号操作已保存。当前本地内容仍保留，请对照后加载服务器版本。');
  }
  async function act(fn:()=>Promise<void>){if(busy)return;setBusy(true);setError('');try{await fn();}catch(e){report(e);}finally{setBusy(false);}}
  const navigate=(fn:()=>void)=>{if(busy)return;if(dirty)setPendingNavigation(()=>fn);else fn();};
  async function create(){
    if(!title.trim())return;
    let ws=workspace;if(!ws){ws=(await api.workspace()).workspaceId;localStorage.removeItem('wa2-d2-project');localStorage.setItem('wa2-d2-workspace',ws);setWorkspace(ws);}
    const value=await api.create(ws,title.trim(),goal.trim());await list(ws);await open(value.projectId);setNewProject(false);setTitle('');setGoal('');setNotice('文章已创建，尚未调用模型。');
  }
  async function save(){
    if(!w)return;const key=actionKey();const savedSerial=editSerial.current;
    try {
      if(stage==='plan')await api.savePlan(w,plan,key);
      else if(stage==='body'&&chapter)await api.saveChapter(w,chapterKey,chapter.chapterRevisionId,doc,key);
      else if(stage==='article')await api.metadata(w,{abstract,keywords:keywords.split(/[、,，]/).map(s=>s.trim()).filter(Boolean)},key);
      else return;
      const persisted=await api.workbench(w.project.projectId);
      if(editSerial.current===savedSerial){hydrate(persisted);setNotice('已保存，正文和版本已从服务器回读。');}
      else {setW(persisted);setNotice('上一份修改已保存。保存过程中新增的修改仍在本地，请再保存一次。');throw new ApiError(409,'LOCAL_CHANGED_DURING_SAVE');}
      await list();
    } catch(e) {
      if(e instanceof ApiError&&e.code==='VERSION_CONFLICT')setConflict(await api.workbench(w.project.projectId));
      report(e);throw e;
    }
  }
  async function prepare(type:Proposal['type']){
    if(!w)return;if(dirty){setError('请先保存当前修改，再确认。');return;}
    const p=await api.prepare(w,type);setProposal(p);
  }
  async function decideProposal(card:Proposal,decision:string){try{await confirmationDecisions.submit(card,decision);setNotice('决定已落库；固定回执可在卡片中查看。');}finally{setProposal(await confirmationRequest(`/proposals/${card.proposalId}`));if(w)hydrate(await api.workbench(w.project.projectId));await list();}}
  async function lookupProposal(card:Proposal){await confirmationDecisions.lookup(card);setProposal(await confirmationRequest(`/proposals/${card.proposalId}`));if(w)hydrate(await api.workbench(w.project.projectId));await list();setNotice('已查询原决定的固定结果。');}
  const changePlan=(next:PlanPayload)=>{setPlan(next);setDirty(true);};
  return <>
    <header><div className="brand"><span className="logo">W</span><div><strong>写作工作台</strong><small>2.0 · 三入口写作工作台</small></div></div><div className="header-actions"><span className="muted">{busy?'正在保存 / 查询…':dirty?'本地有未保存修改':'以服务器版本为准'}</span><button onClick={()=>setRightMode('TASKS')}>任务中心</button><button onClick={()=>navigate(()=>setNewProject(true))}>新建文章</button></div></header>
    <div className={'layout workbench-a '+(assistantOpen?'assistant-open':'assistant-closed')}><aside className="sidebar"><div className="label">写作空间</div><button className={stage==='home'?'active':''} onClick={()=>navigate(()=>setStage('home'))}>文章列表</button><div className="label">当前文章</div><p>{w?.project.title||'尚未选择文章'}</p>{(['plan','body','article'] as const).map((s,i)=><button key={s} disabled={!w} className={stage===s?'active':''} onClick={()=>navigate(()=>setStage(s))}>{i+1}. {s==='plan'?'目标与大纲':s==='body'?'章节正文':'全文与完成'}</button>)}{w?.article&&<section className="chapter-directory" aria-label="章节目录"><div className="label">章节目录 · 勾选批量修改</div><button onClick={()=>setSelectedChapters(selectedChapters.length===w.article!.chapters.length?[]:w.article!.chapters.map(c=>c.chapterKey))}>{selectedChapters.length===w.article.chapters.length?'取消全选':'全选章节'}</button>{w.article.chapters.map(c=><div className={'chapter-nav '+(chapterKey===c.chapterKey?'current':'')} key={c.chapterKey}><input type="checkbox" aria-label={'选择第'+c.chapterIndex+'章'} checked={selectedChapters.includes(c.chapterKey)} onChange={e=>setSelectedChapters(previous=>e.target.checked?[...previous,c.chapterKey]:previous.filter(k=>k!==c.chapterKey))}/><button onClick={()=>navigate(()=>{setStage('body');setChapterKey(c.chapterKey);setDoc(c.contentJson);setEditorEpoch(n=>n+1);setDirty(false);})}><span>{c.chapterIndex}. {c.title}</span><small>{c.chapterRevisionId?'已有正文':'待写'}</small></button></div>)}</section>}<div className="scope-note">本阶段不检索外部资料，不展示伪造引用。</div></aside>
    <main><div className="title-row"><div><h1>{stage==='home'?'从一个想法开始':stage==='plan'?'确定目标与结构':stage==='body'?'写好每一章':'把各章串成全文'}</h1><p className="muted">{w?.project.title||'创建或选择一篇文章，开始手动写作。'}</p></div>{w&&<span className="badge">{w.article?.status||w.plan?.status||'尚无计划'}</span>}</div>
      {error&&<div role="alert" className="error">{error}</div>}{notice&&<div role="status" className="notice">{notice}</div>}
      {w&&(stage==='plan'||stage==='body')&&<StageAiPanel workspace={workspace} project={w.project.projectId} stage={stage} chapterKey={chapterKey} disabled={busy||dirty||!!complete} onSaved={()=>refreshAi(w.project.projectId)} onTask={showTask}/>}
      {w&&stage==='body'&&w.article&&<BatchRewritePanel project={w.project.projectId} chapterKeys={selectedChapters} disabled={busy||dirty} onSaved={()=>refreshAi(w.project.projectId)}/>}
      {pendingCommand()&&<div className="error">上次保存尚未获得确定回执，原操作编号已保留。<button disabled={busy} onClick={()=>void act(resolvePending)}>查询上次保存结果</button>{retryAllowed&&<button disabled={busy} onClick={()=>void act(retryOriginal)}>原编号重发手动保存</button>}</div>}
      {stage==='home'&&<><div className="cards">{projects.map(p=><button className="project-card" key={p.projectId} onClick={()=>navigate(()=>void act(()=>open(p.projectId)))}><strong>{p.title}</strong><span>项目版本 {p.projectVersion}</span></button>)}</div>{projects.length===0&&<div className="empty"><h2>你的下一篇文章，从这里开始</h2><button className="primary" onClick={()=>setNewProject(true)}>创建文章</button></div>}</>}
      {stage==='plan'&&w&&<div className="panel"><div className="row"><span className="badge">{w.plan?'计划第 '+w.plan.revisionNo+'版':'未保存计划'}</span><span className="muted">可手动编辑，或使用上方阶段 AI</span></div>{(['researchQuestion','goals','scope','methodSuggestion','expectedDeliverables'] as const).map((key,i)=><label key={key}>{['核心问题','写作目标','范围','方法建议','预期成果'][i]}<textarea disabled={complete} value={plan[key]||''} onChange={e=>changePlan({...plan,[key]:e.target.value})}/></label>)}<h3>章节大纲</h3>{plan.chapters.map((c,i)=><div className="outline-row" key={c.chapterKey||i}><span>{i+1}</span><div><input aria-label={'第'+(i+1)+'章标题'} disabled={complete} value={c.title} onChange={e=>changePlan({...plan,chapters:plan.chapters.map((ch,j)=>j===i?{...ch,title:e.target.value}:ch)})}/><input aria-label={'第'+(i+1)+'章说明'} disabled={complete} value={c.brief||''} placeholder="本章写作说明" onChange={e=>changePlan({...plan,chapters:plan.chapters.map((ch,j)=>j===i?{...ch,brief:e.target.value}:ch)})}/></div><button disabled={complete||!!w.article?.chapters.some(ch=>ch.chapterRevisionId)} onClick={()=>changePlan({...plan,chapters:plan.chapters.filter((_,j)=>j!==i)})}>删除</button></div>)}<button disabled={complete||!!w.article?.chapters.some(ch=>ch.chapterRevisionId)} onClick={()=>changePlan({...plan,chapters:[...plan.chapters,{title:'新章节',brief:''}]})}>添加章节</button><div className="actions"><button disabled={busy||complete} onClick={()=>void act(save)}>保存计划草稿</button><button className="primary" disabled={busy||dirty||!w.plan||w.plan.status==='CONFIRMED'||complete} onClick={()=>void act(()=>prepare('CONFIRM_PLAN'))}>确认计划</button></div></div>}
      {stage==='body'&&w&&(!w.article?<div className="panel">先保存并确认计划，之后各章会出现在这里。<button onClick={()=>setStage('plan')}>前往计划</button></div>:<><div className="chapter-picker">{w.article.chapters.map(c=><button key={c.chapterKey} className={c.chapterKey===chapterKey?'active':''} onClick={()=>navigate(()=>{setChapterKey(c.chapterKey);setDoc(c.contentJson);setEditorEpoch(n=>n+1);setDirty(false);})}>{c.chapterIndex}. {c.title}</button>)}</div>{chapter&&<><div className="editor-top"><h2>{chapter.title}</h2><span className="muted">{chapter.chapterRevisionId?'正文第 '+chapter.revisionNo+'版':'空白章'}{dirty?' · 本地未保存':''}</span></div><ChapterEditor key={chapterKey+':'+editorEpoch} document={doc} readonly={!!complete} onChange={d=>{setDoc(d);setDirty(true);}}/><div className="actions"><span className="muted">AI 生成和重写使用上方阶段 AI；下方保存手动修改。</span><button className="primary" disabled={busy||complete||!dirty} onClick={()=>void act(save)}>保存正文</button></div></>}</>)}
      {conflict&&<section className="error"><strong>远端已有新稿，你的本地内容未丢弃</strong><p>远端文章版本 {conflict.article?.articleVersion}。选择重新加载会放弃当前本地修改。</p><button onClick={()=>navigate(()=>hydrate(conflict))}>对照后加载远端版本</button></section>}
      {stage==='article'&&w&&(!w.article?<div className="panel">尚未确认计划。</div>:<div className="panel"><label>摘要<textarea disabled={complete} value={abstract} onChange={e=>{setAbstract(e.target.value);setDirty(true);}}/></label><label>关键词<input disabled={complete} value={keywords} onChange={e=>{setKeywords(e.target.value);setDirty(true);}} placeholder="用顿号或逗号分隔"/></label><button disabled={busy||complete||!dirty} onClick={()=>void act(save)}>保存摘要与关键词</button><hr/>{w.article.chapters.map(c=><section className="preview" key={c.chapterKey}><h2>{c.chapterIndex}. {c.title}</h2>{c.bodyMarkdown?<pre>{c.bodyMarkdown}</pre>:<p className="muted">本章尚无正文，暂不能完成文章。</p>}</section>)}<div className="actions"><button className="primary" disabled={busy||dirty} onClick={()=>void act(()=>prepare(complete?'REOPEN_ARTICLE':'COMPLETE_ARTICLE'))}>{complete?'重新打开文章':'确认全文完成'}</button></div></div>)}
    </main><WorkbenchRightPanel mode={rightMode} setMode={setRightMode} taskId={taskId} onTask={showTask} workspace={workspace} project={w?.project.projectId||''} ensureWorkspace={async()=>{const value=await api.workspace();localStorage.setItem('wa2-d2-workspace',value.workspaceId);setWorkspace(value.workspaceId);return value.workspaceId;}} onSaved={refreshAi}/></div>{!assistantOpen&&<button className="assistant-dock" onClick={()=>setRightMode('CHAT')}>✦ 写作助手</button>}
    {newProject&&<div className="overlay"><section className="dialog" role="dialog" aria-modal="true" aria-label="新建文章"><h2>新建文章</h2><label>标题<input autoFocus value={title} onChange={e=>setTitle(e.target.value)}/></label><label>写作想法<textarea value={goal} onChange={e=>setGoal(e.target.value)}/></label><div className="actions"><button disabled={busy} onClick={()=>setNewProject(false)}>取消</button><button className="primary" disabled={busy||!title.trim()} onClick={()=>void act(create)}>创建</button></div></section></div>}
    {proposal&&<div className="overlay"><section className="dialog" role="dialog" aria-modal="true" aria-label="人工确认"><ConfirmationCard card={proposal} unavailable={proposalUnavailable} submitting={busy} decisionPending={!!confirmationDecisions.pending(proposal)} onDecision={(card:Proposal,decision:string)=>void act(()=>decideProposal(card,decision))} onLookup={(card:Proposal)=>void act(()=>lookupProposal(card))}/><button disabled={busy} onClick={()=>setProposal(null)}>关闭（不改变决定）</button></section></div>}
    {pendingNavigation&&<div className="overlay"><section className="dialog" role="dialog" aria-modal="true" aria-label="未保存修改"><h2>当前有未保存修改</h2><p>切换前，你希望如何处理？</p><div className="actions"><button onClick={()=>setPendingNavigation(null)}>留在当前页</button><button disabled={busy} onClick={()=>{if(w)hydrate(w);pendingNavigation();setPendingNavigation(null);}}>放弃更改并继续</button><button className="primary" disabled={busy} onClick={()=>void act(async()=>{await save();pendingNavigation();setPendingNavigation(null);})}>保存并继续</button></div></section></div>}
  </>;
}
createRoot(document.getElementById('root')!).render(<App/>);

import {useEffect,useRef,useState} from 'react';
import {subscribeExecution} from './executionStream.mjs';
import {appendExecutionFact} from './executionFacts.mjs';
import type {AiRun} from './StageAiPanel';
import {ConfirmationCard} from './ConfirmationCard.mjs';
import {ChatMessage} from './ChatMessage.mjs';
import {createConfirmationDecision} from './confirmationDecision.mjs';
import {RunTimeline} from './RunTimeline.mjs';
import './mainAssistant.css';
type Message={messageId:string;role:string;body:string};
type Fact={sourceRunId:string;sequence:number;outputType:string;safePayload:Record<string,unknown>};
type Card={proposalId:string;workspaceId:string;projectId:string;type:string;status:string;displaySummary:string;allowedDecisions?:string[];receipt?:unknown};
const base='/api/writing/v2';
class RequestError extends Error {constructor(public status:number,message:string){super(message);}}
async function request(path:string,method='GET',body?:unknown){const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});const value=await r.json();if(!r.ok)throw new RequestError(r.status,value.code||'请求失败');return value;}
const decisions=createConfirmationDecision({storage:localStorage,request});
type AssistantProps={workspace:string;project:string;ensureWorkspace:()=>Promise<string>;onSaved:(project:string)=>Promise<void>;onTask:(runId:string)=>void};
type Session={conversationId:string;workspaceId:string;title:string;createdAt:string};
export function MainAssistant(props:AssistantProps){
 const {workspace,ensureWorkspace}=props;
 const [sessions,setSessions]=useState<Session[]>([]);const [selected,setSelected]=useState('');const [loading,setLoading]=useState(false);const [error,setError]=useState('');const [next,setNext]=useState(-1);
 useEffect(()=>{let disposed=false;setSessions([]);setSelected('');setError('');if(!workspace)return;setLoading(true);void request(`/workspaces/${workspace}/conversations`).then(async data=>{const savedId=localStorage.getItem('wa2-main-conversation:'+workspace)||'';let items:Session[]=data.items;if(savedId&&!items.some(s=>s.conversationId===savedId)){try{const old=await request('/conversations/'+savedId);if(old.workspaceId===workspace)items=[{conversationId:savedId,workspaceId:workspace,title:old.messages.find((m:Message)=>m.role==='user')?.body.slice(0,40)||'历史会话',createdAt:''},...items];}catch{/* A stale local reference never authorizes another workspace. */}}if(disposed)return;setSessions(items);setNext(data.nextOffset);setSelected(items.some(s=>s.conversationId===savedId)?savedId:items[0]?.conversationId||'');}).catch(e=>{if(!disposed)setError(e.message);}).finally(()=>{if(!disposed)setLoading(false);});return()=>{disposed=true;};},[workspace]);
 function choose(id:string){localStorage.setItem('wa2-main-conversation:'+workspace,id);setSelected(id);}
 async function createSession(){if(loading)return;setLoading(true);setError('');try{const ws=workspace||await ensureWorkspace();const created=await request(`/workspaces/${ws}/conversations`,'POST',{});localStorage.setItem('wa2-main-conversation:'+ws,created.conversationId);const data=await request(`/workspaces/${ws}/conversations`);setSessions(data.items);setNext(data.nextOffset);setSelected(created.conversationId);}catch(e){setError(e instanceof Error?e.message:'会话创建不可用');}finally{setLoading(false);}}
 async function more(){if(next<0||loading)return;setLoading(true);try{const data=await request(`/workspaces/${workspace}/conversations?offset=${next}`);setSessions(previous=>[...previous,...data.items.filter((s:Session)=>!previous.some(p=>p.conversationId===s.conversationId))]);setNext(data.nextOffset);}catch(e){setError(e instanceof Error?e.message:'列表不可用');}finally{setLoading(false);}}
 return <div className="session-assistant"><div className="session-toolbar"><label>当前会话<select aria-label="切换助手会话" value={selected} disabled={loading} onChange={e=>choose(e.target.value)}><option value="" disabled>请选择会话</option>{sessions.map(s=><option value={s.conversationId} key={s.conversationId}>{s.title} · {s.conversationId.slice(0,8)}</option>)}</select></label><button disabled={loading} onClick={()=>void createSession()}>新建会话</button>{next>=0&&<button disabled={loading} onClick={()=>void more()}>更多会话</button>}</div>{error&&<p role="alert" className="error">{error}</p>}{selected?<ConversationAssistant key={workspace+':'+selected} {...props} conversationId={selected}/>:<p className="session-empty">{loading?'读取会话中…':'点击新建会话即可提问，不需要创建文章。'}</p>}</div>;
}
function ConversationAssistant({workspace,project,onSaved,onTask,conversationId}:{workspace:string;project:string;onSaved:(project:string)=>Promise<void>;onTask:(runId:string)=>void;conversationId:string}){
 const conv=conversationId;const pendingKey='wa2-main-pending:'+conv;const draftKey='wa2-main-draft:'+conv;
 const [messages,setMessages]=useState<Message[]>([]);const [text,setText]=useState(localStorage.getItem(draftKey)||'');const [run,setRun]=useState<AiRun|null>(null);const [facts,setFacts]=useState<Fact[]>([]);const [cards,setCards]=useState<Card[]>([]);const [busy,setBusy]=useState(false);const [error,setError]=useState('');
 const saved=useRef(onSaved);saved.current=onSaved;
 const [cardBusy,setCardBusy]=useState<string|null>(null);const [historyOffset,setHistoryOffset]=useState(-1);const [cardsUnavailable,setCardsUnavailable]=useState(false);
 const scrollBox=useRef<HTMLDivElement>(null);const atBottom=useRef(true);
 useEffect(()=>{if(atBottom.current&&scrollBox.current)scrollBox.current.scrollTop=scrollBox.current.scrollHeight;},[messages.length,cards.length,facts.length]);
 const active=!!run&&['QUEUED','RUNNING'].includes(run.status);const locked=busy||active;
 const projectRef=useRef(project);projectRef.current=project;
 const runRef=useRef(run);runRef.current=run;
 async function reload(id=conv){if(!id)return;const [data,page]=await Promise.all([request(`/conversations/${id}`),request(`/conversations/${id}/confirmations?limit=25`)]);setMessages(data.messages);setCards(page.items);setHistoryOffset(page.nextOffset);return data;}
 async function moreCards(){if(historyOffset<0)return;const page=await request(`/conversations/${conv}/confirmations?offset=${historyOffset}&limit=25`);setCards(previous=>[...previous,...page.items.filter((c:Card)=>!previous.some(p=>p.proposalId===c.proposalId))]);setHistoryOffset(page.nextOffset);}
 async function refreshCards(){try{setCards(await Promise.all(cards.map(c=>request(`/proposals/${c.proposalId}`))));setCardsUnavailable(false);}catch(e){setCardsUnavailable(true);throw e;}}
 useEffect(()=>{const old=JSON.parse(localStorage.getItem('wa2-main-pending')||'null');if(old?.conversation===conv){localStorage.setItem(pendingKey,JSON.stringify(old));localStorage.removeItem('wa2-main-pending');}},[conv]);
 // Approval may start a durable continuation without another chat send. Discover it after refresh too.
 useEffect(()=>{if(!conv||!workspace)return;let disposed=false,inflight=false;const tick=async()=>{if(inflight)return;inflight=true;try{const [data]=await Promise.all([request(`/workspaces/${workspace}/runs`),reload(conv)]);if(disposed)return;const latest=data.items.find((r:AiRun)=>r.conversationId===conv);if(latest){if(runRef.current?.rootRunId!==latest.rootRunId)setFacts([]);setRun(latest);}setError(previous=>['Failed to fetch','会话状态暂不可用'].includes(previous)?'':previous);}catch(e){if(!disposed)setError(e instanceof Error?e.message:'会话状态暂不可用');}finally{inflight=false;}};void tick();const timer=setInterval(()=>void tick(),3000);return()=>{disposed=true;clearInterval(timer);};},[conv,workspace]);
 useEffect(()=>{if(!run)return;return subscribeExecution(run.rootRunId,{
  fact:(fact:Fact)=>{setFacts(previous=>appendExecutionFact(previous,fact));if(fact.outputType==='CONFIRMATION_REQUIRED'){const card=fact.safePayload as unknown as Card;setCards(previous=>previous.some(c=>c.proposalId===card.proposalId)?previous:[...previous,card]);void request(`/proposals/${card.proposalId}`).then(value=>setCards(previous=>previous.map(c=>c.proposalId===value.proposalId?value:c)));}if(fact.outputType==='ARTIFACT_SAVED')void saved.current(String(fact.safePayload.projectId||projectRef.current));},
  end:()=>{void request(`/runs/${run.rootRunId}`).then(async value=>{setRun(value);await reload();if(projectRef.current)await saved.current(projectRef.current);});},
  error:(value:{code:string})=>setError(value.code),connectionLost:()=>setError('实时连接正在恢复，不会重复发起任务。')
 });},[run?.rootRunId]);
 useEffect(()=>{if(!cards.length)return;const timer=setInterval(()=>void refreshCards().catch(e=>setError(e.message)),15000);return()=>clearInterval(timer);},[cards]);
 // SSE is a notification channel, not the authority for whether the run has finished.
 useEffect(()=>{if(!run||!active)return;let disposed=false,inflight=false;const tick=async()=>{if(inflight)return;inflight=true;try{const value=await request(`/runs/${run.rootRunId}`);if(disposed)return;setRun(value);if(!['QUEUED','RUNNING'].includes(value.status)){await reload();if(projectRef.current)await saved.current(projectRef.current);}}catch{if(!disposed)setError('实时/状态连接暂不可用；不会重新发起任务。');}finally{inflight=false;}};const timer=setInterval(()=>void tick(),3000);return()=>{disposed=true;clearInterval(timer);};},[run?.rootRunId,active]);
 async function send(){if(locked||!text.trim()||localStorage.getItem(pendingKey))return;setBusy(true);setError('');
  try{await request(`/conversations/${conv}/active-project`,'PUT',{projectId:projectRef.current||null});const body={clientActionId:crypto.randomUUID(),message:text.trim()};localStorage.setItem(pendingKey,JSON.stringify({conversation:conv,body}));
   const accepted=await request(`/conversations/${conv}/turns`,'POST',body);localStorage.removeItem(pendingKey);localStorage.removeItem(draftKey);setText('');setFacts([]);setRun(accepted);await reload(conv);
  }catch(e){if(e instanceof RequestError&&e.status<500)localStorage.removeItem(pendingKey);setError(e instanceof Error?e.message:'任务受理未知，先查询原任务。');}finally{setBusy(false);}}
 async function findPending(){try{const old=JSON.parse(localStorage.getItem(pendingKey)||'null');if(!old||old.conversation!==conv)return;const value=await request(`/conversations/${conv}/turns/lookup?clientActionId=${encodeURIComponent(old.body.clientActionId)}`);localStorage.removeItem(pendingKey);localStorage.removeItem(draftKey);setRun(value);setText('');setError('');await reload(conv);}catch(e){setError(e instanceof Error?e.message:'查询失败');}}
 async function recover(){if(!run||busy)return;setBusy(true);setError('');try{let key=localStorage.getItem('wa2-recover:'+run.rootRunId);if(!key){key=crypto.randomUUID();localStorage.setItem('wa2-recover:'+run.rootRunId,key);}await request(`/runs/${run.rootRunId}/reconcile`,'POST',{clientActionId:key});setRun(null);setFacts([]);await reload();if(projectRef.current)await saved.current(projectRef.current);}catch(e){setError(e instanceof Error?e.message:'核对失败，未重新生成');}finally{setBusy(false);}}
 async function decide(card:Card,decision:string){if(cardBusy||decisions.pending(card))return;setCardBusy(card.proposalId);setError('');try{await decisions.submit(card,decision);await saved.current(card.projectId);}catch(e){setError(e instanceof Error?e.message:'确认结果未知，请查询原决定。');}finally{try{await refreshCards();}catch(e){setError('卡片状态暂不可用，请刷新核验。');}setCardBusy(null);}}
 async function lookupDecision(card:Card){if(cardBusy)return;setCardBusy(card.proposalId);try{await decisions.lookup(card);await refreshCards();await saved.current(card.projectId);setError('');}catch(e){setError('尚未获得确定回执，保留原决定，请稍后继续查询。');}finally{setCardBusy(null);}}
 function cardView(c:Card){return <ConfirmationCard key={c.proposalId} card={c} unavailable={cardsUnavailable} submitting={cardBusy===c.proposalId} decisionPending={!!decisions.pending(c)} onDecision={(card:Card,decision:string)=>void decide(card,decision)} onLookup={(card:Card)=>void lookupDecision(card)}/>;}
 const names:Record<string,string>={MODEL_STARTED:'模型处理中',MODEL_FINISHED:'模型返回',TOOL_CALL_STARTED:'正在调用工具',TOOL_FINISHED:'工具调用返回（业务结果另行核验）',TOOL_BUSINESS_FAILED:'工具业务操作失败',CHILD_RUN_STARTED:'阶段 Agent 开始执行',ARTIFACT_SAVED:'已保存稿件',CONFIRMATION_REQUIRED:'等待人工确认',RUN_TERMINAL:'本次任务结束'};
 return <aside className="assistant">
  <div className="assistant-head"><strong>写作主助手</strong><span className="badge">{active?'运行中':'可以提问'}</span></div>
  <div className="assistant-body" ref={scrollBox} onScroll={()=>{const box=scrollBox.current;if(box)atBottom.current=box.scrollHeight-box.scrollTop-box.clientHeight<80;}}>
   <p className="muted">{project?'写作操作作用于当前文章；确认须点击卡片。':'可以直接问问题，不需要先创建文章。'}</p>
   {messages.map(m=><ChatMessage key={m.messageId} message={m}/>)}
   <div className="pending-confirmations" aria-label="待你确认">{cards.filter(c=>c.status==='OPEN'||decisions.pending(c)).map(cardView)}</div>
   {cards.some(c=>c.status!=='OPEN'&&!decisions.pending(c))&&<details className="confirmation-history"><summary>历史确认（已处理 / 已失效）</summary>{cards.filter(c=>c.status!=='OPEN'&&!decisions.pending(c)).map(cardView)}</details>}
   {historyOffset>=0&&<button onClick={()=>void moreCards().catch(e=>setError(e.message))}>加载更早的确认</button>}
   {error&&<p role="alert" className="error">{error}</p>}{localStorage.getItem(pendingKey)&&<button onClick={()=>void findPending()}>查询上次发送结果</button>}
  </div>
  <div className="assistant-live">{run&&<><RunTimeline facts={facts} status={run.status} compact/><button onClick={()=>onTask(run.rootRunId)}>查看完整任务</button></>}{run?.status==='UNKNOWN'&&<div className="error">本轮有未知结果，不能重复生成。<button disabled={busy} onClick={()=>void recover()}>核对已保存结果并恢复会话</button></div>}{run?.status==='FAILED'&&<p className="error">本轮未全部完成；已保存正文保留，可继续剩余章节。</p>}</div>
  <div className="compose"><textarea aria-label="主助手消息" disabled={locked} value={text} onChange={e=>{setText(e.target.value);localStorage.setItem(draftKey,e.target.value);}} placeholder="直接提问，或让助手生成计划、写章、重写…"/><button className="primary" disabled={locked||!text.trim()||!!localStorage.getItem(pendingKey)} onClick={()=>void send()}>{locked?'运行中…':'发送（主助手）'}</button></div>
 </aside>;
}

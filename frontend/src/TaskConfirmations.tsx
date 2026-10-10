import {useEffect,useState} from 'react';
import {ConfirmationCard} from './ConfirmationCard.mjs';
import {createConfirmationDecision} from './confirmationDecision.mjs';
type Card={proposalId:string;workspaceId:string;projectId:string;status:string};
async function request(path:string,method='GET',body?:unknown){
 const response=await fetch('/api/writing/v2'+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
 const value=await response.json();if(!response.ok)throw Object.assign(new Error(value.code||'请求失败'),{status:response.status});return value;
}
const decisions=createConfirmationDecision({storage:localStorage,request});
export function TaskConfirmations({proposalIds,workspace,onSaved}:{proposalIds:string[];workspace:string;onSaved:(project:string)=>Promise<void>}){
 const [cards,setCards]=useState<Card[]>([]);const [busy,setBusy]=useState('');const [error,setError]=useState('');const [unavailable,setUnavailable]=useState(false);
 const identity=proposalIds.join(',');
 useEffect(()=>{let disposed=false;setCards([]);const read=async()=>{try{const values=await Promise.all(proposalIds.map(id=>request('/proposals/'+id)));if(disposed)return;setCards(values.filter(c=>c.workspaceId===workspace));setUnavailable(false);}catch{if(!disposed){setUnavailable(true);setError('确认卡状态暂不可用，不能提交决定。');}}};void read();const timer=setInterval(()=>void read(),3000);return()=>{disposed=true;clearInterval(timer);};},[identity,workspace]);
 async function decide(card:Card,decision?:string){if(busy)return;setBusy(card.proposalId);setError('');try{if(decision)await decisions.submit(card,decision);else await decisions.lookup(card);const latest=await request('/proposals/'+card.proposalId);setCards(previous=>previous.map(c=>c.proposalId===latest.proposalId?latest:c));await onSaved(card.projectId);}catch(e){setError(e instanceof Error?e.message:'决定结果未知，请查询原决定。');}finally{setBusy('');}}
 return <div className="task-confirmations" aria-label="任务人工确认">{error&&<p role="alert" className="error">{error}</p>}{cards.map(card=><ConfirmationCard key={card.proposalId} card={card} unavailable={unavailable} submitting={busy===card.proposalId} decisionPending={!!decisions.pending(card)} onDecision={(c:Card,d:string)=>void decide(c,d)} onLookup={(c:Card)=>void decide(c)}/>)}</div>;
}

import {useEffect,useRef,useState} from 'react';
import {appendExecutionFact} from './executionFacts.mjs';
import {subscribeExecution} from './executionStream.mjs';
import './execution.css';
export type AiRun={rootRunId:string;projectId:string;conversationId:string;clientActionId:string;operation:string;status:string;result:{errorCode?:string;resultMayExist?:boolean}|null};
type Fact={sourceRunId:string;sequence:number;outputType:string;toolCallId?:string;safePayload:Record<string,unknown>};
const labels:Record<string,string>={RUN_STARTED:'开始执行',MODEL_STARTED:'模型正在处理',MODEL_FINISHED:'模型返回',TOOL_CALL_STARTED:'调用工具',TOOL_CALL:'工具参数已确定',TOOL_FINISHED:'工具已返回',ARTIFACT_SAVED:'稿件已保存',RUN_FINISHED:'任务结束',RUN_TERMINAL:'任务结束',STATUS:'运行状态'};
export function StageAiPanel({workspace,project,stage,chapterKey,disabled,onSaved,onTask}:{workspace:string;project:string;stage:string;chapterKey:string;disabled:boolean;onSaved:()=>Promise<void>;onTask:(id:string)=>void}){
    const [runs,setRuns]=useState<AiRun[]>([]);const [selected,setSelected]=useState<AiRun|null>(null);const [facts,setFacts]=useState<Fact[]>([]);
    const [instruction,setInstruction]=useState('');const [error,setError]=useState('');const [submitting,setSubmitting]=useState(false);
    const onSavedRef=useRef(onSaved);onSavedRef.current=onSaved;
    const uncertain=useRef<{project:string;body:unknown}|null>(null);
    async function refresh(){if(!workspace)return;const r=await fetch(`/api/writing/v2/workspaces/${workspace}/runs`);if(r.ok)setRuns((await r.json()).items);}
    useEffect(()=>{setSelected(null);setFacts([]);void refresh();},[workspace,project]);
    useEffect(()=>{
        if(!selected)return;
        return subscribeExecution(selected.rootRunId,{
            fact:(fact:Fact)=>{setFacts(previous=>appendExecutionFact(previous,fact));if(fact.outputType==='RUN_STARTED')setSelected(previous=>previous?{...previous,status:'RUNNING'}:previous);},
            end:()=>{void (async()=>{const r=await fetch(`/api/writing/v2/runs/${selected.rootRunId}`);if(r.ok)setSelected(await r.json());await refresh();await onSavedRef.current();})();},
            error:(value:{code:string})=>setError(value.code),
            connectionLost:()=>setError('实时连接中断，正在用原任务编号恢复；不会再次生成。')
        });
    },[selected?.rootRunId]);
    const active=selected&&['QUEUED','RUNNING'].includes(selected.status);
    async function start(operation:string){
        if(disabled||submitting||active||!project)return;
        setSubmitting(true);setError('');
        const body={clientActionId:crypto.randomUUID(),operation,instruction,chapterKey:stage==='body'&&operation!=='DRAFT_MISSING_CHAPTERS'?chapterKey:null};
        uncertain.current={project,body};localStorage.setItem('wa2-d2-ai-acceptance',JSON.stringify(uncertain.current));
        try{
            const response=await fetch(`/api/writing/v2/projects/${project}/ai-actions`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(20_000)});
            const result=await response.json();if(!response.ok){if(response.status<500){uncertain.current=null;localStorage.removeItem('wa2-d2-ai-acceptance');}throw new Error(result.code);}
            uncertain.current=null;localStorage.removeItem('wa2-d2-ai-acceptance');setFacts([]);setSelected(result);setInstruction('');onTask(result.rootRunId);await refresh();
        }catch(e){setError(e instanceof Error?e.message:'受理结果未知，已保留原请求，不能自动重发。');}
        finally{setSubmitting(false);}
    }
    const hasUnknown=!!localStorage.getItem('wa2-d2-ai-acceptance');
    async function findOriginal(){
        try{const original=JSON.parse(localStorage.getItem('wa2-d2-ai-acceptance')||'null');if(!original)return;
            const response=await fetch(`/api/writing/v2/projects/${encodeURIComponent(original.project)}/ai-actions/lookup?clientActionId=${encodeURIComponent(original.body.clientActionId)}`);
            const result=await response.json();if(!response.ok)throw new Error(result.code==='ACTION_NOT_FOUND'?'尚未查到任务。已保留原请求，不会自动再次生成。':result.code);
            localStorage.removeItem('wa2-d2-ai-acceptance');uncertain.current=null;setFacts([]);setSelected(result);setError('');onTask(result.rootRunId);await refresh();
        }catch(e){setError(e instanceof Error?e.message:'查询不可用');}
    }
    return <section className="panel stage-ai"><h3>阶段 AI · 真实执行</h3><p className="muted">章节重写先生成候选，在右侧任务详情的确认卡点击后才写入正文；不会替你确认计划或完成全文。未保存修改时请先保存。</p>
        <label>给 AI 的要求<textarea aria-label="阶段AI要求" value={instruction} onChange={e=>setInstruction(e.target.value)} disabled={!!active||submitting}/></label>
        <div className="actions">{stage==='plan'?<button disabled={disabled||submitting||!!active||hasUnknown} onClick={()=>void start('GENERATE_PLAN')}>AI 生成计划</button>:stage==='body'?<><button disabled={disabled||submitting||!!active||hasUnknown} onClick={()=>void start('GENERATE_CHAPTER')}>AI 生成当前章</button><button disabled={disabled||submitting||!!active||hasUnknown||!instruction.trim()} onClick={()=>void start('REWRITE_CHAPTER')}>AI 按要求重写当前章</button><button disabled={disabled||submitting||!!active||hasUnknown} onClick={()=>void start('DRAFT_MISSING_CHAPTERS')}>AI 生成全部缺失章节</button></>:null}</div>
        {submitting&&<p role="status">正在提交任务…</p>}{active&&<p role="status">任务运行中，不能重复发送。离开页面不会取消后台任务。</p>}{hasUnknown&&<p role="alert">上次任务受理结果未知，原编号已保留。<button onClick={()=>void findOriginal()}>查询原任务</button></p>}{error&&<p role="alert" className="error">{error}</p>}
        {selected&&<div className="stage-run-summary"><span className="badge">当前任务 · {selected.status}</span><button onClick={()=>onTask(selected.rootRunId)}>在右侧查看执行过程</button>{selected.result?.errorCode&&<p className="error">{selected.result.errorCode}{selected.result.resultMayExist?' · 已有部分结果，请核对，不自动重跑':''}</p>}</div>}
    </section>;
}

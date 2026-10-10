// One transport per root, shared by stage view and task center. Reads never execute a task.
const connections=new Map();
export function subscribeExecution(root,observer,Source=globalThis.EventSource){
  let connection=connections.get(root);
  if(!connection){
    const source=new Source(`/api/writing/v2/runs/${encodeURIComponent(root)}/events`);
    connection={source,observers:new Set(),facts:[],ended:false,error:null,closed:false};
    connections.set(root,connection);
    const notify=(kind,value)=>{for(const item of [...connection.observers])item[kind]?.(value);};
    const close=()=>{if(!connection.closed){source.close();connection.closed=true;}};
    source.addEventListener('execution-event',e=>{const fact=JSON.parse(e.data);connection.facts.push(fact);notify('fact',fact);});
    source.addEventListener('execution-end',()=>{connection.ended=true;close();notify('end');});
    source.addEventListener('execution-error',e=>{connection.error=JSON.parse(e.data);close();notify('error',connection.error);});
    source.addEventListener('error',()=>notify('connectionLost'));
    connection.close=close;
  }
  connection.observers.add(observer);
  for(const fact of connection.facts)observer.fact?.(fact);
  if(connection.ended)observer.end?.();
  if(connection.error)observer.error?.(connection.error);
  return ()=>{connection.observers.delete(observer);if(!connection.observers.size){connection.close();connections.delete(root);}};
}

export const pendingStorageKey='wa2-d2-pending-command';

export class ApiError extends Error {
  constructor(status,code){super(code);this.status=status;this.code=code;}
}

/** HTTP boundary only. An uncertain response retains the exact original request. */
export function createCommandTransport({storage,fetchFn,timeoutSignal}) {
  const pending=()=>{try{return JSON.parse(storage.getItem(pendingStorageKey)||'null');}catch{return null;}};
  const clear=()=>storage.removeItem(pendingStorageKey);
  async function request(path,method='GET',body){
    const key=body?.clientActionId;
    if(key){
      if(pending())throw new ApiError(409,'PREVIOUS_RESULT_UNKNOWN');
      const projectId=path.match(/^\/projects\/([^/]+)/)?.[1]||storage.getItem('wa2-d2-project')||'';
      storage.setItem(pendingStorageKey,JSON.stringify({path,method,body,workspaceId:storage.getItem('wa2-d2-workspace')||'',projectId}));
    }
    const response=await fetchFn('/api/writing/v2'+path,{method,signal:timeoutSignal(),
      headers:body===undefined?undefined:{'Content-Type':'application/json'},
      body:body===undefined?undefined:JSON.stringify(body)});
    const value=await response.json();
    // A 5xx or interrupted connection does not prove a business write was rolled back.
    if(key&&(response.ok||(response.status>=400&&response.status<500)))clear();
    if(!response.ok)throw new ApiError(response.status,value.code||'REQUEST_FAILED');
    return value;
  }
  async function retryPending(){
    const original=pending();if(!original)throw new ApiError(404,'ACTION_NOT_FOUND');
    clear();return request(original.path,original.method,original.body);
  }
  return {request,pending,clear,retryPending};
}

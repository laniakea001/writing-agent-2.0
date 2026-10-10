// Persist before sending: network failures cannot authorize a second/opposite decision.
export function createConfirmationDecision({storage,request,key=()=>crypto.randomUUID()}){
 const slot=card=>'wa2-confirmation-pending:'+card.proposalId;
 const pending=card=>JSON.parse(storage.getItem(slot(card))||'null');
 return {pending,
  pendingForProject(projectId){for(let i=0;i<storage.length;i++){const name=storage.key(i);if(!name?.startsWith('wa2-confirmation-pending:'))continue;try{const saved=JSON.parse(storage.getItem(name));if(saved.card?.projectId===projectId)return saved.card;}catch{}}return null;},
  async submit(card,decision){
   if(pending(card))throw Error('DECISION_RESULT_UNKNOWN');
   const command={clientActionId:key(),decision};storage.setItem(slot(card),JSON.stringify({...command,card}));
   try{const receipt=await request(`/proposals/${card.proposalId}/decision`,'POST',command);storage.removeItem(slot(card));return receipt;}
   catch(error){if(error.status>=400&&error.status<500)storage.removeItem(slot(card));throw error;}
  },
  async lookup(card){
   const command=pending(card);if(!command)return null;
   const receipt=await request(`/actions/lookup?workspaceId=${encodeURIComponent(card.workspaceId)}&scopeKey=${encodeURIComponent('project:'+card.projectId)}&clientActionId=${encodeURIComponent(command.clientActionId)}`);
   if(!['SUCCEEDED','FAILED'].includes(receipt.actionStatus))throw Error('DECISION_RESULT_UNKNOWN');
   storage.removeItem(slot(card));return receipt;
  }
 };
}

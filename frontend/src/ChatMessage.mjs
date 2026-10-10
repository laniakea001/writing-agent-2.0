import React from 'react';
export function ChatMessage({message}){
 const role=message.role==='user'?'user':'assistant';
 return React.createElement('div',{className:'chat-message role-'+role},React.createElement('small',null,role==='user'?'你':'主助手'),React.createElement('p',null,message.body));
}

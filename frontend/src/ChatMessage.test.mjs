import test from 'node:test';import assert from 'node:assert/strict';import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import {ChatMessage} from './ChatMessage.mjs';
test('assistant message never inherits the sticky assistant panel class',()=>{
 const html=renderToStaticMarkup(React.createElement(ChatMessage,{message:{role:'assistant',body:'待确认',messageId:'m'}}));
 assert.match(html,/chat-message role-assistant/);assert.doesNotMatch(html,/class="[^"]*\sassistant(?:\s|")/);
});

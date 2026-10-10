import { useEffect } from 'react';
import { EditorContent,useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { TableKit } from '@tiptap/extension-table';
import type { JsonDoc } from './types';

/** Same seam as the 1.0 editor: JSON in/out, no legacy routes/store/Python dependencies. */
export function ChapterEditor({document,readonly,onChange}:{document:JsonDoc;readonly:boolean;onChange:(d:JsonDoc)=>void}) {
  const editor=useEditor({extensions:[StarterKit.configure({heading:{levels:[2,3,4]},underline:false,code:false,link:{protocols:['http','https','mailto'],openOnClick:false}}),TableKit],content:document,editable:!readonly,onUpdate:({editor})=>onChange(editor.getJSON() as JsonDoc)});
  // Changing editability is not a user edit; don't emit a synthetic update.
  useEffect(()=>{editor?.setEditable(!readonly,false);},[editor,readonly]);
  // Parent only mounts on an explicit snapshot transition. Never replace a dirty doc on a background refresh.
  return <div className="paper"><div className="toolbar">
    <button disabled={readonly} onClick={()=>editor?.chain().focus().toggleBold().run()}>粗体</button>
    <button disabled={readonly} onClick={()=>editor?.chain().focus().toggleItalic().run()}>斜体</button>
    <button disabled={readonly} onClick={()=>editor?.chain().focus().toggleHeading({level:2}).run()}>标题</button>
    <button disabled={readonly} onClick={()=>editor?.chain().focus().toggleBulletList().run()}>列表</button>
    <button disabled={readonly} onClick={()=>editor?.chain().focus().toggleBlockquote().run()}>引用块</button>
    <button disabled={readonly} onClick={()=>editor?.chain().focus().insertTable({rows:2,cols:2,withHeaderRow:true}).run()}>表格</button>
  </div><EditorContent editor={editor}/></div>;
}

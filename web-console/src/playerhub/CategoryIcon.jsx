// Apache-2.0. Fixed category artwork shared by Manager and the player article.
import React from 'react';
import { HubIcon } from './icons.jsx';
const Sparkles=props=><HubIcon name="sparkles" {...props}/>;
const WandSparkles=props=><HubIcon name="wand-sparkles" {...props}/>;
const Wrench=props=><HubIcon name="wrench" {...props}/>;
const PackageOpen=props=><HubIcon name="package-open" {...props}/>;
const Megaphone=props=><HubIcon name="megaphone" {...props}/>;
export const categoryArt = {
  '功能更新':{Icon:Sparkles,color:'#8b73c5',wash:'#cdbbf66b',name:'星芒'},
  '体验优化':{Icon:WandSparkles,color:'#608cb7',wash:'#a8d0f46b',name:'魔杖'},
  '问题修复':{Icon:Wrench,color:'#519986',wash:'#9bdbc66b',name:'扳手'},
  '整合包更新':{Icon:PackageOpen,color:'#b48b59',wash:'#efd29c6b',name:'包裹'},
  '通知':{Icon:Megaphone,color:'#b078a1',wash:'#e7b7da6b',name:'喇叭'},
};
export default function CategoryIcon({category,size=20,...props}) {
  const {Icon}=categoryArt[category] || categoryArt['功能更新'];
  return <Icon size={size} {...props}/>;
}

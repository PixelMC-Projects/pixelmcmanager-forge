export function extensionPage(items,requestedPage=0,size=3) {
  const pageSize=Number.isFinite(size)?Math.max(1,Math.trunc(size)):3;
  const pages=Math.max(1,Math.ceil(items.length/pageSize));
  const page=Math.min(pages-1,Math.max(0,Number.isFinite(requestedPage)?Math.trunc(requestedPage):0));
  return {page,pages,rows:items.slice(page*pageSize,(page+1)*pageSize)};
}

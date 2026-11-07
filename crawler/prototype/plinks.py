import sys, re, asyncio
from playwright.async_api import async_playwright
kw=re.compile(sys.argv[1])
async def one(ctx,u):
    p=await ctx.new_page()
    try:
        await p.goto(u,timeout=30000,wait_until='networkidle')
        out=set()
        for f in p.frames:
            try:
                for t,h in await f.eval_on_selector_all('a',"els=>els.map(e=>[e.innerText.trim().replace(/\\s+/g,' '),e.href])"):
                    if kw.search(t): out.add((t[:50],h))
            except Exception: pass
        print('##',u,'->',p.url); [print('  ',x) for x in sorted(out)[:12]]
    except Exception as e: print('##',u,'ERR',str(e)[:100])
    await p.close()
async def main():
    async with async_playwright() as pw:
        b=await pw.chromium.launch(); ctx=await b.new_context(ignore_https_errors=True)
        await asyncio.gather(*[one(ctx,u) for u in sys.argv[2:]])
        await b.close()
asyncio.run(main())

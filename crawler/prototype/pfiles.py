import sys, re, asyncio
from playwright.async_api import async_playwright
async def one(ctx,u):
    p=await ctx.new_page()
    try:
        await p.goto(u,timeout=40000,wait_until='networkidle')
        out=set()
        for f in p.frames:
            try:
                for t,h,oc in await f.eval_on_selector_all('a',"els=>els.map(e=>[e.innerText.trim().replace(/\\s+/g,' '),e.href,e.getAttribute('onclick')||''])"):
                    s=(t+h+oc).lower()
                    if re.search(r'\.pdf|\.hwp|download|filedown|요강', s): out.add((t[:40],(h if not h.startswith('javascript') and not h.endswith('#') else oc)[:140]))
            except Exception: pass
        print('##',u); [print('  ',x) for x in sorted(out)[:10]]
    except Exception as e: print('##',u,'ERR',str(e)[:80])
    await p.close()
async def main():
    async with async_playwright() as pw:
        b=await pw.chromium.launch(); ctx=await b.new_context(ignore_https_errors=True)
        await asyncio.gather(*[one(ctx,u) for u in sys.argv[1:]]); await b.close()
asyncio.run(main())

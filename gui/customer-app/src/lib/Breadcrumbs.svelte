<script lang="ts">
  import { page } from '$app/state';

  interface Crumb { label: string; href: string | null; }

  function pretty(seg: string): string {
    if (/^POL-[A-F0-9]+$/.test(seg)) return seg;     // policy numbers — leave verbatim
    if (/^\d+$/.test(seg)) return `#${seg}`;          // numeric ids — prefix with #
    return seg.charAt(0).toUpperCase() + seg.slice(1);
  }

  const crumbs: Crumb[] = $derived.by(() => {
    const segs = page.url.pathname.split('/').filter(Boolean);
    if (segs.length === 0) return [];
    const out: Crumb[] = [{ label: 'Home', href: '/' }];
    let acc = '';
    segs.forEach((seg, i) => {
      acc += '/' + seg;
      out.push({ label: pretty(seg), href: i === segs.length - 1 ? null : acc });
    });
    return out;
  });
</script>

{#if crumbs.length > 0}
  <nav aria-label="Breadcrumb" class="max-w-5xl mx-auto px-6 pt-4 text-xs text-slate-500">
    <ol class="flex items-center gap-1.5 flex-wrap">
      {#each crumbs as c, i}
        {#if i > 0}<li aria-hidden="true" class="text-slate-300">/</li>{/if}
        <li>
          {#if c.href}
            <a href={c.href} class="hover:text-[var(--brand)] hover:underline">{c.label}</a>
          {:else}
            <span class="text-slate-700">{c.label}</span>
          {/if}
        </li>
      {/each}
    </ol>
  </nav>
{/if}

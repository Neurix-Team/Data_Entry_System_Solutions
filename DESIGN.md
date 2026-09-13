# Neurix landing design

The public page presents an approachable document workspace: warm off-white,
baby blue accents, pale blue surfaces, and a paper-to-record illustration. This direction
supersedes the earlier metallic scanning-floor concept at the user's request.

## Scope

- Components: `frontend/src/pages/landing/LandingPage.tsx`.
- Bilingual content: `frontend/src/pages/landing/copy.ts`.
- All styles are scoped to `.nx` in `landing.css`.
- Shared theme and language controls continue to use the existing contexts.
- No changes to authenticated application screens, backend, or account creation.

## Foundations

| Token | Light | Dark |
| --- | --- | --- |
| Background | #f6f8f9 | #101c25 |
| Surface | #ffffff | #182833 |
| Main text | #182d3c | #edf2f5 |
| Secondary text | #5a6e7d | #aab8c2 |
| Accent text | #326f99 | #b0cadd |
| Primary action | #b8def6 | #b8def6 |
| Border | #dce1e5 | #303f49 |

Archivo provides the Latin typography; Readex provides Arabic. Both are self-hosted.
Use logical properties for RTL. Paper and record illustrations retain their own
light surface and dark text in either theme. Main controls have 7px corners;
content cards use 10-18px corners. Avoid metallic textures and glowing counters.

## Page structure

1. Sticky navigation with language, theme, login, and an expandable mobile menu.
2. Hero: benefit-led heading, two actions, document-to-record composition.
3. File formats strip, without fabricated partner logos or usage statistics.
4. Five-stage workflow with selectable previews and complete keyboard navigation.
5. Dark data-control section with workspace, permission, and activity features.
6. Three role cards, then native expandable FAQ items.
7. Demo contact form, followed by a compact footer.

## Behavior and truthfulness

- Hero and workflow previews are explicitly illustrative, not live user data.
- Workflow tabs support Left/Right, Home/End, selected state, and linked panels.
- On mobile, the navigation opens inline and closes when a link is selected.
- Respect reduced motion for scanning, floating, and preview transitions.
- Keep a visible keyboard focus ring and a skip-to-content link.
- The demo form validates locally and opens a `mailto:` message. It does not
  submit to a backend or promise that an email has been sent. The existing
  `demo@neurix.app` address still requires owner verification before public use.

## Responsive layout

- Desktop: split hero, five horizontal tabs, three role cards.
- Below 900px: stacked hero, inline mobile menu.
- Below 600px: stacked workflow, roles, FAQ, and contact form.
- Keep decorative paper and result cards inside the viewport at narrow widths.

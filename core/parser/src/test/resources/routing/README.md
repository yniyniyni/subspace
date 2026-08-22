# Routing parser fixtures

These JSON fixtures are authored for this repository. They contain synthetic
domains, IP categories, URLs, resolver hosts, and names; they do not redistribute
community-profile bytes.

Their field/type shapes were inspected on 2026-08-20 against compatibility-only
references whose repositories declared no licence or reuse grant:

- `russia-inside.json`: https://raw.githubusercontent.com/KazZzeL/allow-domains-happ-routing/master/russia-inside.json
  (SHA-256 `18cfa801b7385ea4a16cfd7cb3e7268d402b44375946ba689d6d8b1a89f782d1`).
- `blocked-only.json`: https://raw.githubusercontent.com/grechixa/happ_routing-profile/main/routing.json
  (SHA-256 `7511eb0da8afad3b625b821f7e8562cbc9bbfb8a68c8cbed401e1293c44dab25`).

The first fixture preserves the observed string-boolean, `RouteOrder`,
`UseChunkFiles`, DNS, and six-bucket shape. The second preserves a real JSON
`false` `GlobalProxy` and proxy-site-only shape. No upstream data is copied.

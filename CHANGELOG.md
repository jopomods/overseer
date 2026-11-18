# Changelog

## [0.7.0](https://github.com/jopomods/overseer/compare/v0.6.0...v0.7.0) (2026-11-18)


### Features

* **core:** world actions with server-thread queue, permission checks and action log ([#62](https://github.com/jopomods/overseer/issues/62)) ([084aec7](https://github.com/jopomods/overseer/commit/084aec7d4b1321470f38fe9a4398a3bed49640a8))
* **flux:** change networks, devices and members from the dashboard ([#63](https://github.com/jopomods/overseer/issues/63)) ([f37bd24](https://github.com/jopomods/overseer/commit/f37bd2480f97c2efb5a7cb85fd0f9df79c4e276c))
* **web:** edit Flux networks, devices and members in the dashboard ([#64](https://github.com/jopomods/overseer/issues/64)) ([475dbde](https://github.com/jopomods/overseer/commit/475dbde0736af4066514899adcdc50cc8b69492c))
* **web:** voltage tiers, naming hints and the priority order for Flux ([#65](https://github.com/jopomods/overseer/issues/65)) ([56d50dd](https://github.com/jopomods/overseer/commit/56d50dd38a5a861ea1b8b2024fae8ab8449ce22f))

## [0.6.0](https://github.com/Jopo-JP/overseer/compare/v0.5.1...v0.6.0) (2026-11-18)


### Features

* fill bar and full/empty time for Flux storages and AE2 power ([#52](https://github.com/Jopo-JP/overseer/issues/52)) ([d1cfefd](https://github.com/Jopo-JP/overseer/commit/d1cfefd602f1671e67139460fc6a7bcae163b4fc))
* history for every inventory item ([#50](https://github.com/Jopo-JP/overseer/issues/50)) ([31a12a0](https://github.com/Jopo-JP/overseer/commit/31a12a0149c2dd1acc429ba21ddbd34be6e940c0))
* storage page lists inventory changes and icons; overview shows deficits and the profiler ([#55](https://github.com/Jopo-JP/overseer/issues/55)) ([d762325](https://github.com/Jopo-JP/overseer/commit/d7623258253d80dc59e6158cbf6936cb536147fd))
* tick profiler grouped by mod ([#53](https://github.com/Jopo-JP/overseer/issues/53)) ([26b82ad](https://github.com/Jopo-JP/overseer/commit/26b82ad4ce38b5e263390a6a86027443dc1bef09))
* **web:** live chart ranges of 5 and 15 minutes ([#51](https://github.com/Jopo-JP/overseer/issues/51)) ([3a84e4e](https://github.com/Jopo-JP/overseer/commit/3a84e4e6138704cb724fa8e838216d299a22b808))
* **web:** named and player CPUs first, automation CPUs folded away ([#49](https://github.com/Jopo-JP/overseer/issues/49)) ([9cfda92](https://github.com/Jopo-JP/overseer/commit/9cfda925e4ab5777fec199e5638739c3bc2431a9))


### Bug Fixes

* **core:** reloading an ME network page no longer answers 404 ([#59](https://github.com/Jopo-JP/overseer/issues/59)) ([108c6d5](https://github.com/Jopo-JP/overseer/commit/108c6d5383f81ff8f3ad4bcd37e9046cf03b4894))
* **flux:** devices in unloaded chunks no longer fail the whole collection ([#48](https://github.com/Jopo-JP/overseer/issues/48)) ([6100ffc](https://github.com/Jopo-JP/overseer/commit/6100ffc0f3cdab4f284554e039ff9613f2c36c96))
* **web:** correct job progress, also after a restart ([#54](https://github.com/Jopo-JP/overseer/issues/54)) ([6ebe438](https://github.com/Jopo-JP/overseer/commit/6ebe438d1e1cb60ed33258191da3416f237eee2e))

## [0.5.1](https://github.com/Jopo-JP/overseer/compare/v0.5.0...v0.5.1) (2026-11-18)


### Bug Fixes

* **web:** colour codes on every page, profiler keeps its last result ([#46](https://github.com/Jopo-JP/overseer/issues/46)) ([7d900f5](https://github.com/Jopo-JP/overseer/commit/7d900f522bcc87f49a83d62e93eebdda85a4ac9b))

## [0.5.0](https://github.com/Jopo-JP/overseer/compare/v0.4.2...v0.5.0) (2026-11-18)


### Features

* tick profiler for block entities and entities ([#44](https://github.com/Jopo-JP/overseer/issues/44)) ([a886e05](https://github.com/Jopo-JP/overseer/commit/a886e05048547823b0ef3eb216f39a2a4b2b84f2))

## [0.4.2](https://github.com/Jopo-JP/overseer/compare/v0.4.1...v0.4.2) (2026-11-18)


### Bug Fixes

* **ae2:** names without formatting codes, CPU numbers as in game ([#41](https://github.com/Jopo-JP/overseer/issues/41)) ([c528004](https://github.com/Jopo-JP/overseer/commit/c528004488cb61d71e7bd90fbd177b8158eda138))
* keep colour codes in names and show them in colour (setting to turn off) ([#43](https://github.com/Jopo-JP/overseer/issues/43)) ([687d792](https://github.com/Jopo-JP/overseer/commit/687d792ad951192048a8fcbeed1ccb00afca1334))

## [0.4.1](https://github.com/Jopo-JP/overseer/compare/v0.4.0...v0.4.1) (2026-11-18)


### Bug Fixes

* **web:** stable sankey order, storage colours, no page jumps ([#38](https://github.com/Jopo-JP/overseer/issues/38)) ([5c5ff16](https://github.com/Jopo-JP/overseer/commit/5c5ff1622265fdaaaf90897b2c102c2c50f4acf1))

## [0.4.0](https://github.com/Jopo-JP/overseer/compare/v0.3.0...v0.4.0) (2026-11-18)


### Features

* access requests for ME networks with in-game notifications ([#29](https://github.com/Jopo-JP/overseer/issues/29)) ([30aa0b3](https://github.com/Jopo-JP/overseer/commit/30aa0b3e427f65f1102f6d8c23dac9b799ec8811))
* **ae2:** drives and cells with fill, states and history ([#34](https://github.com/Jopo-JP/overseer/issues/34)) ([c8fa1b0](https://github.com/Jopo-JP/overseer/commit/c8fa1b02abb9a96179bc1b5a3d8ffa625d8b03bb))
* **ae2:** FTB Teams party members see the owner's ME networks ([#27](https://github.com/Jopo-JP/overseer/issues/27)) ([3f055e0](https://github.com/Jopo-JP/overseer/commit/3f055e0355b1a97423dd18f06162769f84f88010))
* **ae2:** inventory scan every 10 s with contents list and changes ([#31](https://github.com/Jopo-JP/overseer/issues/31)) ([4ceb585](https://github.com/Jopo-JP/overseer/commit/4ceb5854d50e55d6e2590ad8a4620247a6d99724))
* **ae2:** live crafting CPUs with jobs, progress and history ([#32](https://github.com/Jopo-JP/overseer/issues/32)) ([e62be87](https://github.com/Jopo-JP/overseer/commit/e62be8717c4d7ce1d4446eb0137f24898d35800a))
* **ae2:** live job lines in crafting CPU cards ([#33](https://github.com/Jopo-JP/overseer/issues/33)) ([078404e](https://github.com/Jopo-JP/overseer/commit/078404ecbb72907bc1a14e741e761f3fbe8bc793))
* **ae2:** ME network discovery, power and inventory overview (M2a) ([#23](https://github.com/Jopo-JP/overseer/issues/23)) ([438fec9](https://github.com/Jopo-JP/overseer/commit/438fec9dd49d2ea2a3ff1030f4eb254b9f793f16))
* **ae2:** ME networks without controller: hidden, adoptable, /ov ae2 register ([#30](https://github.com/Jopo-JP/overseer/issues/30)) ([d0c60c2](https://github.com/Jopo-JP/overseer/commit/d0c60c23390bf6778a136651830ac7c98e541fba))
* **ae2:** optional view access for players who placed part of a grid ([#37](https://github.com/Jopo-JP/overseer/issues/37)) ([7750cd8](https://github.com/Jopo-JP/overseer/commit/7750cd80c9a60c99bff25bf55f4052e88f49d31f))
* **ae2:** watchlist with targets, deficits and forecast ([#35](https://github.com/Jopo-JP/overseer/issues/35)) ([ce4d4ea](https://github.com/Jopo-JP/overseer/commit/ce4d4eac56d656279c594fc32a21f02d150ab091))
* item icons with client exporter and mod texture fallback ([#36](https://github.com/Jopo-JP/overseer/issues/36)) ([d50ca6e](https://github.com/Jopo-JP/overseer/commit/d50ca6eff2acc47cc6e6b8ef21f786a0caa83534))
* share ME networks with single players (view only) ([#28](https://github.com/Jopo-JP/overseer/issues/28)) ([d231f04](https://github.com/Jopo-JP/overseer/commit/d231f047742a882bfe4d698af70d7f614633473e))


### Bug Fixes

* **ae2:** ignore empty inventory while a grid boots; distinct short-range axis labels ([#26](https://github.com/Jopo-JP/overseer/issues/26)) ([6ac9d6c](https://github.com/Jopo-JP/overseer/commit/6ac9d6cea4a925b90f820fff8ac67ec2ab814c12))

## [0.3.0](https://github.com/Jopo-JP/overseer/compare/v0.2.0...v0.3.0) (2026-11-18)


### Features

* admin storage page with retention, purge, VACUUM and backup ([#18](https://github.com/Jopo-JP/overseer/issues/18)) ([5f99a4f](https://github.com/Jopo-JP/overseer/commit/5f99a4f483dca300cd6a2393f083ccb8ac50934f))
* **core:** recordings, anonymized real-world fixture and simulator ([#12](https://github.com/Jopo-JP/overseer/issues/12)) ([c9a393d](https://github.com/Jopo-JP/overseer/commit/c9a393d2e2c9f35c8124cca7f4d4b89748645705))
* **core:** time-weighted rollups, retention and history API ([#14](https://github.com/Jopo-JP/overseer/issues/14)) ([ad06f89](https://github.com/Jopo-JP/overseer/commit/ad06f89a9d56b7231345bfaffe9aa61d3e941ba6))
* verify M1b with 87 minutes of real server data; min-max chart bands ([#21](https://github.com/Jopo-JP/overseer/issues/21)) ([ce14b36](https://github.com/Jopo-JP/overseer/commit/ce14b36bdd97a4fb7c09acb26b39eb539dcdb4b6))
* **web:** dev:mock mode and faster parallel CI ([#20](https://github.com/Jopo-JP/overseer/issues/20)) ([76a1bfb](https://github.com/Jopo-JP/overseer/commit/76a1bfbf0a8b78f008e966d452414ae5c7fcd954))
* **web:** energy flow Sankey per zone ([#17](https://github.com/Jopo-JP/overseer/issues/17)) ([aa25557](https://github.com/Jopo-JP/overseer/commit/aa25557e55bd83f75b8f29f581446189aea2591a))
* **web:** history charts with uptime and calendar axis ([#15](https://github.com/Jopo-JP/overseer/issues/15)) ([f59e0e0](https://github.com/Jopo-JP/overseer/commit/f59e0e02f755d12a899479ca9b6a83d2ce601d26))
* **web:** smooth charts, axis in settings, energy flow device detail ([#22](https://github.com/Jopo-JP/overseer/issues/22)) ([e51ead7](https://github.com/Jopo-JP/overseer/commit/e51ead7742ae164c9c17000e9d9fbe1d08ad16f6))
* **web:** stacked zone chart over time ([#19](https://github.com/Jopo-JP/overseer/issues/19)) ([c157b7e](https://github.com/Jopo-JP/overseer/commit/c157b7ee9058ba988ce137f31314b9a4ef6e641e))
* zone overrides with edit rights and storage forecast ([#16](https://github.com/Jopo-JP/overseer/issues/16)) ([158c370](https://github.com/Jopo-JP/overseer/commit/158c3707e3b92f921d15aa18a8bab7888f65fb01))

## [0.2.0](https://github.com/Jopo-JP/overseer/compare/v0.1.0...v0.2.0) (2026-11-17)


### Features

* **core:** HTTP server, authentication and permissions ([#5](https://github.com/Jopo-JP/overseer/issues/5)) ([9d6059a](https://github.com/Jopo-JP/overseer/commit/9d6059a4a8b20dd1a63774891f0fc986e9897325))
* **flux:** Flux Networks provider with membership-based access ([#8](https://github.com/Jopo-JP/overseer/issues/8)) ([8e524d8](https://github.com/Jopo-JP/overseer/commit/8e524d8a79cf14faa645bd2944b0f26c200fff6e))
* **forge:** run Overseer on Forge with /ov commands and server provider ([#7](https://github.com/Jopo-JP/overseer/issues/7)) ([08e7097](https://github.com/Jopo-JP/overseer/commit/08e7097708606bc9bb190faeae1c44de8bc05978))
* **web:** app shell, login, overview, server and account pages ([#9](https://github.com/Jopo-JP/overseer/issues/9)) ([2b4f7e9](https://github.com/Jopo-JP/overseer/commit/2b4f7e99335be80e87318b53e858cf7570c540cb))
* **web:** Flux Networks energy pages ([#10](https://github.com/Jopo-JP/overseer/issues/10)) ([af67fa9](https://github.com/Jopo-JP/overseer/commit/af67fa9c66c1327dcd4f1b333503e8ad19039b65))
* **web:** installable PWA ([#11](https://github.com/Jopo-JP/overseer/issues/11)) ([23366d3](https://github.com/Jopo-JP/overseer/commit/23366d3efb089fa0697707596ff834bde2095167))

## 0.1.0 (2026-11-17)


### Features

* **core:** snapshot pipeline and SQLite storage ([#2](https://github.com/Jopo-JP/overseer/issues/2)) ([c68cbe1](https://github.com/Jopo-JP/overseer/commit/c68cbe18326cfd57f427b75e4e0c40979832ca6d))

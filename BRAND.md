# Brand and product identity

## Architecture of names

- **Family Opportunity Engine (FOE) / 家庭机会引擎:** open Core, protocols, schemas, SDK, and ecosystem.
- **Jianyu / 见隅:** official reference application built on FOE's public interfaces.

Jianyu is not the engine's only valid product. A third-party product may use FOE without using the Jianyu name or interface.

## Jianyu language

Primary Chinese tagline:

> **从一隅，看见更多可能。**

Project tagline:

> **More doors. Fewer prescriptions.**
>
> **多看见几扇门，少规定一条路。**

Positioning line:

> **AI for parents, not another AI tutor for kids.**
>
> **给家长的 AI，不是又一个给孩子的 AI 老师。**

Privacy line:

> **Your child is not our dataset.**
>
> **你的孩子，不是我们的数据集。**

These lines express product behavior, not decoration. UI copy must avoid prescription, diagnosis, child ranking, missed-opportunity anxiety, streak pressure, and claims of guaranteed development.

## Logo direction

The visual direction is an open corner or doorway with several possible nodes/routes beyond it: seeing outward from one small opening. It must work in one color and should not use a robot, AI brain, graduation cap, child silhouette, growth chart, or mascot as the primary mark.

The intended visual tone is a quiet field guide rather than a children's learning machine: deep ink, warm amber, muted field green, and paper white. The Android reference App now contains a provisional one-color open-corner vector mark and a matching multicolor launcher treatment so the interface no longer relies on a generic AI-sparkle symbol for product identity. This is an implementation asset, not a declaration that the official logo is finished; a separate visual and trademark review is still required before release.

The reference theme defines complete light and dark semantic palettes. Primary actions use muted field green, secondary/leave-space surfaces use warm amber, backgrounds remain paper-like rather than clinical white, and error/sponsorship states retain a separate high-salience role. Forks replace these through the brand layer; protocol and stored family events never depend on a color token.

## Fork-friendly branding

All product names, taglines, localized copy, links, colors, typography, assets, support contacts, feature flags, default Providers, and default Policies are replaceable through BrandConfig or adjacent configuration. A fork should never need to edit Stable Core or rewrite stored events to rebrand.

Apache-2.0 allows modification and commercial redistribution while preserving required notices. It does not grant permission to imply official status or endorsement. A modified product may truthfully say “Based on Family Opportunity Engine” and should publish its own privacy, security, hosting, and support claims.

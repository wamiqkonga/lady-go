# Lady Go design system (customer + driver Android apps)

Status: v1, 2026-10-17. Applies to `mobile/android-customer` and `mobile/android-driver`. The two apps share
no module, so `ui/theme/` and `ui/components/` are **identical copies** (only the package differs) - change
one, change both. iOS follows the same tokens when it starts.

## 1. Who it is for, and what it must feel like

A women-only ride service in a conservative, high-trust market: riders are often travelling alone, sometimes
with family watching over their shoulder; drivers are women doing a job that carries social risk. The product
must feel **calm, warm, protective and grown-up** - not a pink "women's app" cliche, not a cold dispatch tool.

Principles
1. **Safety is visible, never scary.** Reassurance is always one glance away (verified driver, ride PIN, Call
   112) but the screen is never alarmist: red is reserved for the emergency action and real errors.
2. **One thing per screen.** A rider booking at night, a driver at a red light: one clear question, one big
   primary action, generous space. No dense dashboards.
3. **Warm, plain language.** Short sentences, no jargon, no blame in errors ("We couldn't reach the server -
   check your connection and try again").
4. **Privacy cues.** Lock icons and a one-line reason wherever we ask for something sensitive (location, selfie,
   PIN). Sensitive screens are also `FLAG_SECURE`.
5. **Big, forgiving touch targets** (>= 56 dp primary, >= 48 dp anywhere), high contrast (WCAG AA), works in
   bright sun and one-handed. Respect system font scale and RTL (Urdu later).

## 2. Colour

| Role | Light | Dark | Used for |
|---|---|---|---|
| primary (Plum) | `#6A2C70` | `#E7B6EC` | brand, primary buttons, active step |
| primaryContainer (Blush lilac) | `#F6E4F5` | `#4C1B52` | soft brand fills, selected rows |
| secondary (Rose) | `#C74B6E` | `#FFB1C4` | small warm accents (rating, destination pin) |
| secondaryContainer | `#FFE0E8` | `#5E1A33` | highlights |
| success / verified (Sage teal) | `#1F7A63` | `#8EDBC4` | verified, confirmed, online, pickup dot |
| successContainer | `#DDF3EB` | `#0F3D31` | success banners, "Online" |
| warning (Amber) | `#8A5A00` | `#F5C76A` | waiting / attention |
| warningContainer | `#FFF0D1` | `#4A3200` | warning banners |
| error / emergency | `#B3261E` | `#FFB4AB` | errors and the Call 112 button only |
| background | `#FFF9FA` | `#171019` | screen (warm blush white) |
| surface | `#FFFFFF` | `#211826` | cards |
| surfaceVariant | `#F7EFF4` | `#2D2231` | inputs, quiet fills |
| outline | `#D9C9D3` | `#5A4A5E` | hairlines |
| onSurface / onSurfaceVariant | `#2B2030` / `#6E5F72` | `#F0E4F2` / `#C9B8CC` | text |

Brand gradient (hero areas only): Plum `#6A2C70` -> deeper `#4A1D5A`.

## 3. Type

Nunito (rounded, friendly, highly legible; bundled variable font, OFL licence in `mobile/THIRD_PARTY_FONTS.md`).
Display 32/40 bold - screen hero; Headline 24/32 semibold - screen title; Title 18/26 semibold - card title;
Body 16/24 regular (never smaller for essential text); Label 14/20 semibold - buttons/chips; Caption 12/16.
Numbers that matter (fare, PIN, countdown) use Display/Headline weight.

## 4. Shape, space, elevation

Radii: 12 (chips, small), 16 (inputs), 24 (cards), 32 (hero/sheets), pill (buttons). 8-dp grid; screen gutter 20;
card padding 20; section gap 16-24. Flat by default: surfaces separated by tone + 1-dp outline, elevation only on
the one floating thing per screen.

## 5. Components (`ui/components/`)

`PrimaryButton`, `SecondaryButton`, `QuietButton`, `EmergencyButton` (Call 112: error outline + phone icon);
`LadyGoCard`; `InfoBanner` (info / success / warning / error); `StatusChip`; `LadyGoTextField`; `StepDots`;
`BrandHeader` (gradient hero with logo mark); `LogoMark`; `RouteCard` (pickup dot - connector - destination pin);
`FareCard`; `PinDisplay` (digit boxes + lock + "share only in person"); `DriverCard` (initials avatar, verified
badge, vehicle, plate pill); `LoadingState`; `Money.format` (minor units -> "Rs 150.58"; the API sends paise).

## 6. Copy rules

Warm, direct, second person. Reassure before asking ("Your photo is only shown to your driver"). Name the
benefit of every permission. Errors say what happened and what to do next. Never expose raw codes.

# Liquid Glass in TV Home: what Apple documents and how we draw it

Research notes (October 2026) behind the glass in TV Home. **[A]** = Apple documentation or WWDC25,
**[3P]** = third-party reverse engineering, **[Inf]** = our own choice. Apple publishes no blur radii,
opacities or angles; every number below is a starting value tuned on the TCL panel.

## The material
- **[A]** Glass is a functional layer for controls and navigation that floats above content; never in the content layer, never glass on glass (WWDC25 219, HIG Materials).
- **[A]** It *lenses* (bends light at its edges) rather than only blurring; bigger glass has deeper shadows and stronger lensing; specular highlights follow the shape.
- **[A]** Two variants: **regular** (blurs and adapts luminosity; small pieces flip light/dark with the background) and **clear** (very translucent, only over media, and it needs a dimming layer, about 30-35% black).
- **[3P]** Apple's private filter refracts in a band at the edge (inner/outer refraction height and amount) and blurs mildly inside (ktiays/GlassExplorer, sspai).
- **[Inf] Our recipe** (`LiquidGlassView`, `GlassDrawable`): pre-blurred backdrop, mesh-warped near the rim with a convex-squircle profile (kube.io), light tint, hairline rim brightest top-left and bottom-right, faint inner highlight, shadow only when lifted.

## Per element
| Element | Apple | TV Home |
|---|---|---|
| Dock tray | No documented tray; tvOS 26 dock is transparent with sparkling edges [A/press] | Regular glass pane, lens at the rim, takes the banner's colours |
| Top Shelf | Liquid Glass system experience; full-screen previews, dock slides away [A] | Banner after 1.5 s, trailer after 2 s more, Up browses posters |
| Buttons | `.glass` always glass on tvOS; `.glassProminent` tinted; icon buttons are circles, text buttons capsules [A] | Round glass header buttons; focused = white fill, dark symbol |
| Control Center | Liquid Glass, keeps the picture visible [A]; clear glass + local dimming [Inf] | Floating glass tiles, no panel; focused tile white, scale 1.06 |
| Sidebars, panels | Larger glass does not flip light/dark, is more opaque [A] | Settings: matte graphite with grain, glass rows |
| Menus | Pop open, morph out of the button that opened them [A] | Not used yet |
| Volume | Sliders turn into glass while adjusted [A] | Vertical glass capsule that fills with the level |
| App icons | 2-5 layers; focused icon lifts, sways, surface illuminates; specular edge in tvOS 26 [A/press] | Spring lift 1.14x, tilt toward the D-pad move, moving sheen |
| Text on glass | Monochrome, flips with the glass; colour goes on the background, not the label [A] | White on dark glass, black on focused white |

## Motion
- **[A]** Focusing animations are fast and prominent, unfocusing slower and subtler; fast navigation shortens both (WWDC17 "Focus Interaction").
- **[A]** Motion should be interruptible and keep velocity ("Designing Fluid Interfaces", WWDC18).
- **[Inf]** `Spring.java`: response/damping springs, retargeted with velocity. Focus in 0.30 s / 0.72, out 0.42 s / 1.0, fast moves 0.16-0.20 s.

## Accessibility [A]
Reduce Transparency makes glass frostier; Increase Contrast makes it black or white with a border; Reduce Motion removes elastic effects.

Sources: developer.apple.com (Glass, glassEffect, GlassEffectContainer, Adopting Liquid Glass, HIG Materials, Top Shelf, App icons, Buttons), WWDC25 sessions 219, 356, 323, 284; WWDC17 224; kube.io/blog/liquid-glass-css-svg; github.com/ktiays/GlassExplorer; sspai.com/post/100983; Kyant Backdrop.

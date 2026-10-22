# Artwork spec sheet

How the app's pictures work, what each page can use, and how to make them so they drop in without touching the code.

## The idea

The app has a list of **slots** (`art/slots.json`). Each slot is one picture: a page background, a button frame, an icon and so on.
You put a picture in the folder for its page, named after the slot. When the app is built, every picture it finds is used.
**A slot with no picture, or with a broken one, falls back to the app's built-in look, so nothing breaks while the set is incomplete,
and any picture can be swapped for a new one later by replacing the file.**

    art/
      global/      page backgrounds, card and button frames, divider, ribbon
      icons/       the icon set
      library/     library banner, empty-library picture
      reader/      reading-page backgrounds
      player/      player-bar background
      voices/      voices banner
      audiobook/   audiobook banner
      settings/    settings banner
      splash/      the opening picture

File name = slot id + `.png` or `.webp` (for example `art/player/player_bar.png`). One file per slot.
Check your folder any time with `python3 scripts/art_tool.py check` (the build does this too), and see what is still missing with
`python3 scripts/art_tool.py status`.

## Rules for every picture

1. **No words in pictures.** All text in the app stays real text (large-text settings, screen readers and translations depend on it).
2. **Sizes are for a 3x phone screen** (3 px = 1 dp). Use the size in the table, or the same proportions up to double (it is scaled down).
3. **Transparent background where the table says yes** (PNG or WebP with alpha, no white box or checkerboard).
4. **Keep each file under 700 KB.** WebP at quality 85-90 is usually plenty. Large files slow cheap phones.
5. **One light source**: warm light from the upper left, the same in every picture.
6. **Readable on both themes.** Parchment is light cream; Study is dark walnut. Icons and frames need to look right on both: brass with a thin dark outline works.
7. **Textures must tile.** The left edge meets the right edge and the top meets the bottom with no visible seam. Keep them low in contrast: text sits on top.
8. **Frames are cut into nine pieces.** The corner size in the table is the width of the corners in pixels. Keep the centre plain, put the decoration in the corners and edges, and make the four edges repeatable (no one-off ornament in the middle of an edge).
9. **Do not copy or imitate real brands, characters or other people's artwork.**

## Text

**No picture contains words, letters or numbers.** The only exception is the logo lettering inside the splash picture (the logo is part of
that artwork). Every label, title and message is drawn by the app on top, so it stays sharp, scales with the phone's text-size setting,
is read out by screen readers and can be translated. Each slot below says what text, if any, the app writes over it.

## Interaction states

A control can be **normal, pressed, selected (switched on or chosen) or disabled**. To keep the number of pictures small:

| Control | How the four states are made |
|---|---|
| **Buttons and chips** | Four separate frames, all exactly the same size and shape so they swap without a jump: `global_button_frame` (normal), `global_button_frame_pressed`, `global_button_frame_selected`, `global_button_frame_disabled`. If a state picture is missing the app uses the normal one and adjusts it (pressed darker, selected brighter, disabled faded). |
| **Icons** | One picture each. The app makes the states: pressed = the normal touch ripple, selected = a soft ring added behind it, disabled = 40% opacity and grey. |
| **Cards, panels, backgrounds, banners, ribbon** | One picture. The app adds the pressed ripple and any shadow. |

## Palette (from the splash picture)

| Colour | Hex | Use |
|---|---|---|
| Walnut | `#2B1A10` to `#3B2416` | dark backgrounds |
| Leather (oxblood) | `#6B2B24` | frames, panels |
| Brass | `#C99A4B` / `#D9A95B` | edges, icons, highlights |
| Parchment | `#F6ECD8` | light backgrounds, page |
| Cream | `#F1E6D0` | text and icons on dark |
| Forest green | `#1F3D2F` | secondary panels |
| Ribbon blue | `#2C86D9` | the bookmark ribbon only |

## Style prompt to paste into the image tool (with each request)

> A consistent set of UI artwork for a cosy, premium audiobook reading app set in an old mansion study. Materials: dark polished walnut
> wood grain, oxblood red leather with subtle stitching, antique brass trim with soft highlights, cream parchment paper, a single bright blue
> silk bookmark ribbon. Warm candle light from the upper left, soft shadows, gentle depth, no harsh glare. Clean, simple, readable shapes at
> small sizes; slightly rounded, friendly proportions; the same line weight and lighting in every piece. **No text, no letters, no numbers,
> no logos.** Flat, evenly lit, centred. Transparent background where requested.

Then add one line saying which slot it is, for example:
"Make `icon_play`: a play triangle in antique brass with a thin dark outline, 192 x 192 px, transparent background, centred with a small margin."
For a frame: "Make `global_card_frame`: a rounded rectangle frame with a thin antique brass edge and a plain dark oxblood leather centre,
192 x 192 px, corners about 60 px, transparent outside the frame, the centre perfectly plain and flat."
Make a whole group (all the icons) in one go so they match, and keep using the same style prompt.

## Order to do them in

1. **Pilot:** `global_card_frame`, `global_button_frame`, `global_button_frame_pressed`, `player_bar`, the play, pause, previous, next icons and `global_bg_dark`. Build, look at the Library and the reader on your phone, and adjust.
2. The rest of the icons, `global_bg_light`, `reader_bg_light`, `reader_bg_dark`, `global_ribbon`, `global_divider`.
3. The banners and pictures.

## Slot list

Generated from `art/slots.json` (run `python3 scripts/art_tool.py doc` after editing it).

<!-- slots:start -->

### Folder `art/global/`

#### `global_bg_light`

- **Purpose and where it appears:** Behind every screen in the Parchment (light) theme, under all text and cards. Fills the whole screen by repeating.
- **File:** `art/global/global_bg_light.png` (or `.webp`), seamless tile
- **Size:** 512 x 512 px (at 3x this is 170 x 170 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** Warm cream parchment (#F6ECD8) with faint paper fibres and very slight ageing. No stains, no borders, no vignette. Flat, evenly lit, no 3D depth. Contrast between lightest and darkest area under 8%.
- **Text:** None. Never any lettering.
- **Interaction states:** One picture only.
- **Used by the app now:** yes

#### `global_bg_dark`

- **Purpose and where it appears:** Behind every screen in the Study (dark) theme.
- **File:** `art/global/global_bg_dark.png` (or `.webp`), seamless tile
- **Size:** 512 x 512 px (at 3x this is 170 x 170 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** Very dark walnut (#1C120B to #2B1A10) with fine wood grain running horizontally; a hint of warmth, no knots or cracks. Flat, no highlights. Contrast between lightest and darkest under 10%.
- **Text:** None.
- **Interaction states:** One picture only.
- **Used by the app now:** yes

#### `global_card_frame`

- **Purpose and where it appears:** Behind each document card in the Library, and later behind other panels (voice cards, audiobook panels). Shown at any size from about 280 x 120 dp to full width; the corners stay about 20 dp.
- **File:** `art/global/global_card_frame.png` (or `.webp`), stretchable frame, corners 60 px
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Rounded rectangle. Outer edge: thin antique brass line (about 4 px) with a soft highlight on the top-left and a darker edge on the bottom-right. Inside: oxblood leather (#6B2B24) with very faint stitching just inside the edge. Slight 3D bevel on the edge only; the middle is completely flat and plain so text is readable. Drop shadow is drawn by the app, not in the picture.
- **Text:** None baked in. The app writes the title, author and buttons over it in cream or ink.
- **Interaction states:** Normal only. The app adds the pressed ripple.
- **Used by the app now:** yes

#### `global_button_frame`

- **Purpose and where it appears:** Behind the main buttons (for example Resume, Audiobook, Create audiobook) and selectable chips. Shown at 48 dp high and as wide as its text.
- **File:** `art/global/global_button_frame.png` (or `.webp`), stretchable frame, corners 40 px
- **Size:** 192 x 96 px (at 3x this is 64 x 32 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Pill-like rounded shape. Antique brass rim (about 3 px) with a top-left highlight; inside, polished oxblood leather with a soft top-to-bottom gradient (lighter at the top). Gentle raised 3D look, as if the button stands slightly proud. Centre plain.
- **Text:** None baked in. The app draws the label in cream (#F1E6D0) on top.
- **Interaction states:** This is the NORMAL state.
- **Used by the app now:** yes

#### `global_button_frame_pressed`

- **Purpose and where it appears:** Same buttons as the normal frame, while a finger is pressing them.
- **File:** `art/global/global_button_frame_pressed.png` (or `.webp`), stretchable frame, corners 40 px
- **Size:** 192 x 96 px (at 3x this is 64 x 32 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Identical shape and size to the normal frame, but pressed in: the leather a little darker, the brass rim dimmer, the highlight gone and a faint inner shadow along the top edge. It must line up exactly with the normal frame.
- **Text:** None.
- **Interaction states:** This is the PRESSED state.
- **Used by the app now:** yes

#### `global_button_frame_selected`

- **Purpose and where it appears:** The same buttons and chips when they are switched on or chosen (for example the chosen pace, quality or theme option).
- **File:** `art/global/global_button_frame_selected.png` (or `.webp`), stretchable frame, corners 40 px
- **Size:** 192 x 96 px (at 3x this is 64 x 32 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Same shape and size as the normal frame, but lit: a brighter brass rim with a warm inner glow, the leather a little lighter and warmer, as if candlelight falls on it. Must line up exactly with the normal frame.
- **Text:** None baked in. The app draws the label in cream (#F1E6D0) on top.
- **Interaction states:** This is the SELECTED state.
- **Used by the app now:** yes

#### `global_button_frame_disabled`

- **Purpose and where it appears:** The same buttons when they cannot be used right now (for example Start while another audiobook is being made).
- **File:** `art/global/global_button_frame_disabled.png` (or `.webp`), stretchable frame, corners 40 px
- **Size:** 192 x 96 px (at 3x this is 64 x 32 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Same shape and size as the normal frame, but dull and flat: the brass rim grey-brown with no highlight, the leather desaturated and darker, no 3D lift. Must line up exactly with the normal frame.
- **Text:** None baked in. The app draws the label in cream (#F1E6D0) on top.
- **Interaction states:** This is the DISABLED state.
- **Used by the app now:** yes

#### `global_divider`

- **Purpose and where it appears:** Between sections, for example above each heading in Settings. Spans the screen width at 16 dp high.
- **File:** `art/global/global_divider.png` (or `.webp`), banner
- **Size:** 1080 x 48 px (at 3x this is 360 x 16 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** A thin horizontal brass rule fading to transparent at both ends, with one small flourish or diamond in the middle. Soft highlight on top, no heavy shadow.
- **Text:** None.
- **Interaction states:** One picture only.
- **Used by the app now:** yes

#### `global_ribbon`

- **Purpose and where it appears:** The draggable handle (thumb) of the reading-position slider in the reader, 32 dp wide and 64 dp high, hanging down from the slider line.
- **File:** `art/global/global_ribbon.png` (or `.webp`), icon
- **Size:** 96 x 192 px (at 3x this is 32 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** A short length of glossy bright blue silk ribbon (#2C86D9) with a swallow-tail (V-notch) end, a soft sheen down the middle and a darker edge. Slight 3D curl at the top, hanging straight down. Same blue as the ribbon in the logo.
- **Text:** None.
- **Interaction states:** One picture only; the app scales it up slightly while dragging.
- **Used by the app now:** yes


### Folder `art/icons/`

#### `icon_play`

- **Purpose and where it appears:** The large round play button in the reader and the Resume button on library cards. Shown at 34 dp (reader) and 24 dp (cards).
- **File:** `art/icons/icon_play.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Play triangle.
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_pause`

- **Purpose and where it appears:** The same big button when audio is playing. Shown at 34 dp.
- **File:** `art/icons/icon_pause.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Pause (two bars).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_previous`

- **Purpose and where it appears:** Reader player bar, left end: previous chapter. 24 dp.
- **File:** `art/icons/icon_previous.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Previous chapter (bar and triangle).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_next`

- **Purpose and where it appears:** Reader player bar, right end: next chapter. 24 dp.
- **File:** `art/icons/icon_next.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Next chapter.
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_sentence_back`

- **Purpose and where it appears:** Reader player bar: previous sentence. 24 dp.
- **File:** `art/icons/icon_sentence_back.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Previous sentence (single chevron pointing left).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_sentence_forward`

- **Purpose and where it appears:** Reader player bar: next sentence. 24 dp.
- **File:** `art/icons/icon_sentence_forward.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Next sentence (single chevron pointing right).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_bookmarks`

- **Purpose and where it appears:** Reader top bar and the bookmarks list. 24 dp.
- **File:** `art/icons/icon_bookmarks.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Bookmarks (ribbon or bookmark).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_speed`

- **Purpose and where it appears:** The speed chip in the reader. 18 dp.
- **File:** `art/icons/icon_speed.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Speed (dial or gauge).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_sleep`

- **Purpose and where it appears:** The sleep timer chip in the reader. 18 dp.
- **File:** `art/icons/icon_sleep.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Sleep timer (moon or hourglass).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_voice`

- **Purpose and where it appears:** The Voice chip in the reader and the Voices tab in the bottom bar. 18 to 24 dp.
- **File:** `art/icons/icon_voice.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Voice (head with sound waves, or speech arcs).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_headphones`

- **Purpose and where it appears:** Audiobook buttons: library cards, the reader top bar and the Create audiobook button. 24 dp.
- **File:** `art/icons/icon_headphones.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Headphones (audiobook).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_add`

- **Purpose and where it appears:** The Add document button in the Library. 24 dp.
- **File:** `art/icons/icon_add.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Add (plus).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_library`

- **Purpose and where it appears:** The Library tab in the bottom bar. 24 dp.
- **File:** `art/icons/icon_library.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Library (books on a shelf).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_settings`

- **Purpose and where it appears:** The Settings tab in the bottom bar. 24 dp.
- **File:** `art/icons/icon_settings.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Settings (cog).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_edit`

- **Purpose and where it appears:** Rename in menus. 24 dp.
- **File:** `art/icons/icon_edit.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Edit (pencil).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_delete`

- **Purpose and where it appears:** Remove / delete in menus. 24 dp.
- **File:** `art/icons/icon_delete.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. Delete (bin).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes

#### `icon_more`

- **Purpose and where it appears:** The three-dot menu on library cards. 24 dp.
- **File:** `art/icons/icon_more.png` (or `.webp`), icon
- **Size:** 192 x 192 px (at 3x this is 64 x 64 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Antique brass (#D9A95B) with a soft top-left highlight and a thin dark brown (#2B1A10) outline about 3 px wide so it reads on both parchment and walnut; simple bold shape, slight raised 3D feel, same line weight as every other icon, centred with a 12 px margin. More options (three dots).
- **Text:** None. No letters or numbers.
- **Interaction states:** ONE picture. The app makes the other states: pressed = the normal touch ripple; selected = a soft ring added behind it by the app; disabled = 40% opacity and grey.
- **Used by the app now:** yes


### Folder `art/library/`

#### `library_empty`

- **Purpose and where it appears:** The Library screen when no documents have been added, centred above the message, shown at about 200 dp square with rounded corners.
- **File:** `art/library/library_empty.png` (or `.webp`), picture
- **Size:** 620 x 620 px (at 3x this is 206 x 206 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** The golden retriever with round glasses resting its head on a stack of leather books in the warm study (the current picture). Warm candle light from the upper left, soft depth of field. If you redraw it keep the same dog, same mood and the same palette.
- **Text:** None baked in. The app writes "Your library is empty" and the instructions.
- **Interaction states:** One picture only.
- **Used by the app now:** yes

#### `library_header`

- **Purpose and where it appears:** A banner above the document list on the Library screen, full width, about 100 dp high. Must look right when the middle band is cropped to fit.
- **File:** `art/library/library_header.png` (or `.webp`), banner
- **Size:** 1080 x 300 px (at 3x this is 360 x 100 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** Wide cinematic strip of the study: dark bookshelves with leather spines, a brass candle sconce and warm window light, with the left and right thirds quiet and dark so the top bar text stays readable. Soft depth, warm lighting from the upper left. Fades to dark walnut at the bottom edge.
- **Text:** None baked in (the app writes the screen title).
- **Interaction states:** One picture only.
- **Used by the app now:** yes

#### `library_cover_default`

- **Purpose and where it appears:** RESERVED. Will show on a book card when no cover picture has been added, at about 100 x 150 dp.
- **File:** `art/library/library_cover_default.png` (or `.webp`), picture
- **Size:** 300 x 450 px (at 3x this is 100 x 150 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** Plain oxblood leather book cover with a brass-lined border and a small empty brass plate in the centre; no text. Slight 3D bevel and soft light from the upper left.
- **Text:** None baked in; the app will write the title on the plate.
- **Interaction states:** One picture only.
- **Used by the app now:** not yet (reserved)


### Folder `art/reader/`

#### `reader_bg_light`

- **Purpose and where it appears:** Behind the book text in the reader when the Parchment theme is on. Repeats to fill the page.
- **File:** `art/reader/reader_bg_light.png` (or `.webp`), seamless tile
- **Size:** 512 x 512 px (at 3x this is 170 x 170 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** Even cream paper (#F6ECD8 to #F9F0DE), subtle fibre, practically flat. Must not distract from reading: contrast under 5%.
- **Text:** None.
- **Interaction states:** One picture only.
- **Used by the app now:** yes

#### `reader_bg_dark`

- **Purpose and where it appears:** Behind the book text in the reader when the Study theme is on.
- **File:** `art/reader/reader_bg_dark.png` (or `.webp`), seamless tile
- **Size:** 512 x 512 px (at 3x this is 170 x 170 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** Near-black warm brown (#1C120B), with a barely visible leather or wood grain. Contrast under 5%.
- **Text:** None.
- **Interaction states:** One picture only.
- **Used by the app now:** yes


### Folder `art/player/`

#### `player_bar`

- **Purpose and where it appears:** The panel at the bottom of the reader that holds the slider and the play controls; full width, about 150 dp high. The top edge is the visible edge.
- **File:** `art/player/player_bar.png` (or `.webp`), stretchable frame, corners 48 px
- **Size:** 384 x 144 px (at 3x this is 128 x 48 dp)
- **Transparent background:** YES, transparent outside the shape
- **Look (colours, materials, lighting, 3D):** Oxblood leather (#6B2B24 to #4A1D18) with a brass trim line along the top edge (about 4 px, soft highlight) and faint stitching; slightly raised 3D look at the top only. The rest is plain so controls stay readable.
- **Text:** None.
- **Interaction states:** One picture only.
- **Used by the app now:** yes


### Folder `art/voices/`

#### `voices_header`

- **Purpose and where it appears:** Banner at the top of the Voices tab, full width, about 100 dp high, middle band visible.
- **File:** `art/voices/voices_header.png` (or `.webp`), banner
- **Size:** 1080 x 300 px (at 3x this is 360 x 100 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** The study desk with a brass microphone and a stack of books, the dog with headphones to one side; warm candle light from the upper left; left and right thirds quiet and dark.
- **Text:** None baked in.
- **Interaction states:** One picture only.
- **Used by the app now:** yes

#### `voices_empty`

- **Purpose and where it appears:** RESERVED. Will be shown on the Voices tab before any voice is saved, about 200 dp square.
- **File:** `art/voices/voices_empty.png` (or `.webp`), picture
- **Size:** 620 x 620 px (at 3x this is 206 x 206 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** The golden retriever in glasses wearing headphones, ears up, listening; same warm study palette and lighting as the other dog picture.
- **Text:** None.
- **Interaction states:** One picture only.
- **Used by the app now:** not yet (reserved)


### Folder `art/audiobook/`

#### `audiobook_header`

- **Purpose and where it appears:** Banner at the top of the Audiobook screen, full width, about 100 dp high.
- **File:** `art/audiobook/audiobook_header.png` (or `.webp`), banner
- **Size:** 1080 x 300 px (at 3x this is 360 x 100 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** The study with an open book and a brass reading lamp, warm glow, the dog asleep on a rug at one side; quiet dark thirds left and right.
- **Text:** None baked in.
- **Interaction states:** One picture only.
- **Used by the app now:** yes

#### `audiobook_ready`

- **Purpose and where it appears:** RESERVED. Will be shown when an audiobook is finished, about 200 dp square.
- **File:** `art/audiobook/audiobook_ready.png` (or `.webp`), picture
- **Size:** 620 x 620 px (at 3x this is 206 x 206 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** The golden retriever in glasses with headphones, smiling contentedly beside a finished stack of books and a blue ribbon bookmark.
- **Text:** None.
- **Interaction states:** One picture only.
- **Used by the app now:** not yet (reserved)


### Folder `art/settings/`

#### `settings_header`

- **Purpose and where it appears:** Banner at the top of Settings, full width, about 100 dp high.
- **File:** `art/settings/settings_header.png` (or `.webp`), banner
- **Size:** 1080 x 300 px (at 3x this is 360 x 100 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** Brass instruments on walnut: a globe, a pocket watch and a candle, warm light from the upper left; left and right thirds quiet and dark.
- **Text:** None baked in.
- **Interaction states:** One picture only.
- **Used by the app now:** yes


### Folder `art/splash/`

#### `splash_art`

- **Purpose and where it appears:** Full-screen picture shown for about 1.6 seconds when the app opens (already in the app; replacing the file changes it). Shown cropped to the phone screen, so keep the key subject in the middle 60% and the dog and logo away from the very top and bottom.
- **File:** `art/splash/splash_art.png` (or `.webp`), picture
- **Size:** 841 x 1870 px (at 3x this is 280 x 623 dp)
- **Transparent background:** no, fully opaque
- **Look (colours, materials, lighting, 3D):** As the current artwork: cosy mansion study at golden hour, the gilded "Touch Reader" sign over an open book with a blue ribbon, the dog on a stack of books, a steaming mug, a window onto a garden.
- **Text:** The logo lettering is part of this picture on purpose. The tagline "Mundy Morning Studios - Always Professional. Always Free." is written by the app, so do not bake it in.
- **Interaction states:** One picture only.
- **Used by the app now:** yes

<!-- slots:end -->

## For developers

- Slots are read by name at run time (`ui/art/Art.kt`), so a missing or undecodable picture is simply skipped.
- The Gradle task `syncArt` copies `art/<page>/<slot>.png|webp` to `res/drawable-nodpi/art_<slot>.*` (those copies are git-ignored).
- A new slot is added in `art/slots.json`, then `python3 scripts/art_tool.py doc`, then used with `ArtIcon`, `Modifier.artTexture`, `Modifier.artFrame` or `ArtImage`.
- The pictures are not covered by the Apache licence: see `NOTICE`.

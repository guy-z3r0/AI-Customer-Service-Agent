# Style contract — AI Customer Service Agent

Theme: aurora
Density: comfortable
Accent: cyan

Nocturne, dark only. JavaFX desktop. Written 2026-08-04. Contract version 1.

This file is the single source for every visual value in this app. It is read, not
loaded — nothing here is a runtime library. If a screen needs something this file does
not define, the file grows by one named part; no screen gets a local exception, because
the first exception turns the contract into a suggestion.

---

## 1. Values

Separation method: shadow. Radius profile: soft.

## Surfaces, text and borders

| Token | Value |
|---|---|
| `surface.backdrop` | `#0B0E1A` |
| `surface.panel` | `#151A2C` |
| `surface.raised` | `#1E2440` |
| `surface.overlay` | `#242C4A` |
| `border.hairline` | `#2E3659` |
| `border.strong` | `#5A6491` |
| `text.primary` | `#E9ECF6` |
| `text.secondary` | `#AEB7D1` |
| `text.muted` | `#8A94B5` |
| `text.disabled` | `#565F82` |
| `accent` | `#4FD6EE` |
| `on-accent` | `#0B0E1A` |
| `data.azure` | `#6BA6FF` |
| `data.gold` | `#E3B54C` |
| `data.jade` | `#57C98A` |
| `data.rose` | `#F2798C` |
| `data.violet` | `#B18CF5` |

## States, resolved per surface

| Surface | hover | pressed | selected |
|---|---|---|---|
| `surface.panel` | `#232839` | `#2C3141` | `#1D3447` |
| `surface.raised` | `#2C314B` | `#343A53` | `#253D58` |
| `surface.overlay` | `#313955` | `#3A415C` | `#2A4461` |

Focus ring: 2px `#4FD6EE`, 2px gap outside the control edge.
Selected leading bar: 2px `#4FD6EE`.
Disabled: background `#1E2440`, text `#565F82`, border `#2E3659`.

## Spacing

`s0` 0px · `s1` 2px · `s2` 4px · `s3` 8px · `s4` 12px · `s5` 16px · `s6` 24px · `s7` 32px · `s8` 48px · `s9` 64px

## Radii

`r-sm` 4px · `r-md` 8px · `r-lg` 14px · `r-pill` 999px

## Type at comfortable

| Step | Size | Weight | Line spacing |
|---|---|---|---|
| `display` | 28px | 600 | +4px |
| `title` | 22px | 600 | +4px |
| `heading` | 17px | 600 | +3px |
| `subhead` | 15px | 500 | +3px |
| `body` | 14px | 400 | +6px |
| `body-strong` | 14px | 600 | +6px |
| `label` | 12px | 500 | +2px |
| `caption` | 11px | 400 | +2px |
| `mono-data` | 13px | 500 | +2px |
| `mono-small` | 11px | 400 | +2px |

## Sizing at comfortable

| Element | Value |
|---|---|
| control height (button, field, dropdown) | 34px |
| table row height | 36px |
| list row height | 40px |
| side rail width, expanded | 220px |
| side rail width, collapsed | 56px |
| top bar height | 52px |
| tab strip height | 38px |
| status bar height | 26px |
| icon size, inline | 16px |
| icon size, rail | 20px |
| minimum window | 720px × 480px |
| rail collapse threshold | 1000px |

## Floor check

- `text.primary` on `surface.overlay`: 11.60:1
- `text.secondary` on `surface.overlay`: 6.84:1
- `text.muted` on `surface.overlay`: 4.55:1
- `accent` on `surface.overlay`: 7.94:1
- `on-accent` on `accent`: 11.16:1
- `border.strong` on `surface.panel`: 3.02:1

### Fonts

Inter — Regular 400, Medium 500, SemiBold 600. Fallback: Inter, Segoe UI, SF Pro Text,
Roboto, sans-serif.
JetBrains Mono — Regular 400, Medium 500. Fallback: JetBrains Mono, Consolas, Menlo,
monospace.
Bundle the static faces and load them with `Font.loadFont()` at startup;
`-fx-font-weight` only resolves for a family that ships the face.

### Elevation and motion

`shadow.panel` — `dropshadow(gaussian, rgba(0,0,0,0.38), 14, 0, 0, 4)`
`shadow.overlay` — `dropshadow(gaussian, rgba(0,0,0,0.50), 28, 0, 0, 10)`

JavaFX CSS has no `transition`, so state changes are instant. Only these animate, in
code: toast and tooltip at 120ms, dialog fade and rail collapse at 180ms, indeterminate
progress at 240ms. Nothing else moves.

JavaFX cannot express letter-spacing or line-height. Tracking is absent from the ramp
rather than specified and ignored; vertical rhythm is `TextFlow.lineSpacing` in extra
pixels, and single-line controls take their height from the sizing table.

### Theme rules

- Panels and cards carry `shadow.panel`; dialogs and popovers carry `shadow.overlay`.
  No hairline borders on panels or cards, apart from the one on the status bar's top edge.
- The accent carries the active rail item, the primary button, the focus ring, and one
  number on the screen. The data palette appears in charts and nowhere else.
- Check every new part against a four-hundred-row table before calling it done. Wide
  spacing hides gaps in the vocabulary that a dense screen exposes at once.

---

## 2. Parts

All 38, in six groups. Each states its structure, its values, and what it does when
its content does not fit. The list is closed at these six groups; a screen needing
something else gets a new named part in section 4.

### Shell

#### `window-frame`
The application window's own content root. Background `surface.backdrop`. No radius,
no padding — children own their own spacing. Minimum window size from the sizing
table; below it the window stops shrinking rather than reflowing.
**Overflow:** the window never scrolls as a whole. Scrolling belongs to
`scroll-region` inside a panel.

#### `side-rail`
Primary navigation down the left edge. Background `surface.panel`, width from the
sizing table, item height equal to control height, item padding `s4` (12px).
The active item takes the `selected` state: accent overlay plus the 2px accent bar on
its leading edge. Inactive items are `text.secondary`, hover only.
**Overflow:** more items than fit scroll vertically with no visible scrollbar track.
Below the rail collapse threshold the rail becomes icon-only at collapsed width and
nothing else on screen moves.

#### `top-bar`
Title and global actions. Background `surface.panel`, height from the sizing table,
horizontal padding `s5` (16px), gap between actions `s3` (8px). Title uses
`heading`. Separated from content by the theme's separation method.
**Overflow:** the title truncates at the end with an ellipsis before any action
button is dropped. Actions never wrap to a second row.

#### `tab-strip`
Horizontal tabs within a region. Height from the sizing table, tab padding `s4` (12px), label `label`. Active tab is `text.primary` with a 2px accent underline;
inactive is `text.muted`.
**Overflow:** tabs scroll horizontally. They never wrap and never shrink their label
below the ramp.

#### `status-bar`
Persistent single-line state at the window's bottom edge. Background
`surface.panel`, height from the sizing table, padding `s3` (8px), text `caption` in
`text.muted`. Separated by a `border.hairline` on its top edge in every theme,
including the shadow themes — this is the one hairline Aurora and Mulberry use.
**Overflow:** left segment truncates at the end; right segment is fixed and never
truncates.

#### `plugin-host`
The region a plugin renders into. Background `surface.backdrop`, padding `s5` (16px).
A plugin composes from this vocabulary and declares one accent from the six. It
cannot set backgrounds, corner radii, font sizes or spacing. Harsh, but it is the
only version where eight plugins by four authors still look like one program.
**Overflow:** the host clips its child. A plugin that wants scrolling uses
`scroll-region`.


### Surfaces

#### `panel`
The main content container for a region. Background `surface.panel`, radius `r-md` (8px),
padding `s5` (16px). Separation by the theme's method.
**Overflow:** content taller than the panel scrolls inside a `scroll-region`. The
panel never grows past its grid cell.

#### `card`
A bounded object inside a panel — a project, a file, a result. Background
`surface.raised`, radius `r-md` (8px), padding `s4` (12px), gap between cards `s4` (12px).
**Overflow:** a card grows vertically to fit its content and never scrolls
internally. Long text inside truncates at the end.

#### `stat-tile`
One number and its label. Background `surface.raised`, radius `r-md` (8px), padding `s4` (12px). Label above in `label`/`text.muted`, value below in `mono-data` at `display`
size. Exactly one tile per screen may render its value in the app accent — the single
number that matters. All others use `text.primary`.
**Overflow:** the value never wraps and never shrinks. A value too long for the tile
is abbreviated by the app, not by the layout.

#### `section-header`
A labelled break inside a panel. Text `subhead` in `text.secondary`, margin above
`s6` (24px), below `s4` (12px). No background, no radius, no rule line — the spacing
is the separation.
**Overflow:** truncates at the end.

#### `divider`
A 1px `border.hairline` line. Margin `s4` (12px) on the axis it divides, none on the
other. Never used where a `section-header` or panel edge already separates.

#### `scroll-region`
Any area that scrolls. Scrollbar width `s4` (12px) in both densities, thumb
`border.strong`, track transparent, thumb radius `r-pill` (999px), no arrow buttons. The
scrollbar overlays content rather than reserving a gutter.
**Overflow:** vertical only by default. Horizontal scrolling is allowed only inside
`table` and `tab-strip`.


### Data

#### `table`
Rows and columns. Header background `surface.raised`, header text `label` in
`text.muted`, row height from the sizing table, cell padding `s3` (8px), row divider
1px `border.hairline`. Numeric cells use `mono-data`, right-aligned; text cells use
`body`, left-aligned. Row hover and selected per the state model. No zebra striping —
the row divider does that job.
**Overflow:** the header pins while the body scrolls vertically. Columns scroll
horizontally when they exceed the width. A cell truncates at the end and reveals on
hover; it never wraps inside a row, because a wrapping cell breaks row height and
row height is what makes a long table readable.

#### `list-row`
A single-line item in a vertical list. Height from the sizing table, padding `s4` (12px), gap between leading icon and label `s3` (8px), text `body`. Divider 1px
`border.hairline` between rows. Hover and selected per the state model.
**Overflow:** label truncates at the end and reveals on hover. Trailing metadata is
fixed-width and truncates last.

#### `tree`
Hierarchical rows. Same height, padding and text as `list-row`. Indent per level
`s5` (16px). Disclosure chevron in `text.muted` at inline icon size, leading the row.
**Overflow:** deep indentation truncates the label rather than scrolling
horizontally. Depth is not limited by the contract.

#### `chart-frame`
The container a chart is drawn inside. Background `surface.raised`, radius `r-md` (8px),
padding `s5` (16px). Title `subhead`, legend `caption` in `text.muted` above the plot
area with a `s3` (8px) gap. Series colours come from the data palette in its listed order.
The frame is in scope. What is drawn inside it is not — no axis, legend layout,
tooltip or chart-type rules live here.
**Overflow:** the frame is fixed; the chart scales to it. A chart never scrolls.

#### `thumbnail-grid`
A grid of image previews. Cell radius `r-sm` (4px), gap `s4` (12px), cell background
`surface.raised` with a 1px `border.hairline`. The border is what keeps unknown
content colour off the panel, which matters most in Prism. Caption below each cell in
`caption`/`text.secondary`.
**Overflow:** the grid reflows to fewer columns and scrolls vertically. Cells never
change aspect ratio.

#### `key-value`
Paired label and value, stacked or two-column. Label `label` in `text.muted`, value
`body` in `text.primary`, numeric values in `mono-data`. Row gap `s3` (8px), column
gap `s5` (16px).
**Overflow:** the value wraps to a second line; the label never wraps.

#### `tag-badge`
A small pill carrying a category or state. Height is the `label` size plus `s3` (8px) vertical padding, horizontal padding `s3` (8px), radius `r-pill` (999px), text `label`.
Background is a data palette hue; text on it is `surface.backdrop`. A tag whose
colour comes from user content uses that colour as background with
`surface.backdrop` text, and is the only place in the contract where a colour outside
the palette is legal.
**Overflow:** truncates at the end. Tags never wrap mid-word and a row of tags never
becomes two rows — the overflow becomes a count.


### Input

#### `button`
Four kinds, one geometry. Height from the sizing table, horizontal padding `s5` (16px), radius `r-sm` (4px), text `body-strong`, icon gap `s2` (4px).

| Kind | Background | Text | Border |
|---|---|---|---|
| primary | app accent | `surface.backdrop` | none |
| secondary | `surface.raised` | `text.primary` | 1px `border.strong` |
| ghost | transparent | `text.secondary` | none |
| destructive | `data.rose` | `surface.backdrop` | none |

At most one primary button per screen. Hover, pressed, focus and disabled per the
state model.
**Overflow:** the label truncates at the end. A button never wraps to two lines and
never shrinks its text below the ramp.

#### `text-field`
Height from the sizing table, padding `s4` (12px), radius `r-sm` (4px), background
`surface.raised`, 1px `border.strong`, text `body`, placeholder `text.muted`. Focus
per the state model. A field in error takes a 1px `data.rose` border and pairs with
`inline-error`.
**Overflow:** single-line fields scroll their content horizontally. Multi-line fields
grow to a stated maximum then scroll.

#### `dropdown`
Closed state matches `text-field` exactly, plus a chevron in `text.muted` at inline
icon size with `s3` (8px) trailing padding. Open list uses `surface.overlay`, radius
`r-md` (8px), item height equal to control height, item padding `s4` (12px).
**Overflow:** the list scrolls after eight items. It never exceeds the window and
never reverses direction to escape the window edge — it flips above the field.

#### `toggle`
Track width `s9` (64px) at half height, radius `r-pill` (999px). Off: track `surface.raised`, knob
`text.muted`. On: track the app accent, knob `surface.backdrop`. Label trails the
toggle with `s3` (8px) gap in `body`.

#### `checkbox`
Box is `s5` (16px) square, radius `r-sm` (4px), 1px `border.strong` unchecked on
`surface.raised`. Checked fills with the app accent and draws its mark in
`surface.backdrop`. Label trails with `s3` (8px) gap in `body`.

#### `radio`
Same size and states as `checkbox` with radius `r-pill` (999px). Group spacing `s3` (8px).
Use only when every option is visible at once; more than five options is a
`dropdown`.

#### `slider`
Track height `s1` (2px), radius `r-pill` (999px), track `surface.raised`, filled portion the app
accent. Thumb `s5` (16px) diameter in `text.primary`, radius `r-pill` (999px). Value label trails in
`mono-data`.
**Overflow:** the track fills its container. It has no minimum beyond the control
height.

#### `search-field`
A `text-field` with a leading magnifier icon in `text.muted` at inline icon size,
`s3` (8px) gap, and a trailing clear affordance that appears only when the field has
content. Radius `r-pill` (999px) in the round and soft profiles, `r-sm` (4px) elsewhere.

#### `file-picker`
A `text-field` showing the path, in `mono-small`, with a trailing secondary
`button` reading a verb. Never a bare browse button with no path shown.
**Overflow:** the path truncates at the **start**, not the end — the filename is the
part worth keeping. This is the one part in the contract that truncates leading.


### Feedback

#### `dialog`
Background `surface.overlay`, radius `r-lg` (14px), padding `s6` (24px), `shadow.overlay` in
every theme. Title `title`, body `body` in `text.secondary`, actions right-aligned
with `s3` (8px) gap and `s6` (24px) above. Scrim over the window is `#000000` at 50%.
**Overflow:** body content scrolls inside the dialog; the title and action row stay
fixed. The dialog never exceeds three quarters of the window height.

#### `confirm`
A `dialog` with exactly two actions: a secondary `button` to dismiss and a primary
`button` to proceed. One line of body text. No form fields.

#### `destructive-confirm`
A `confirm` whose proceeding action is a destructive `button`. The body names what
will be lost and is not phrased as a question. The destructive action is never the
default focus — focus lands on dismiss.

#### `toast`
Transient message, bottom-right, background `surface.overlay`, radius `r-md` (8px),
padding `s4` (12px), `shadow.overlay`, text `body`. A leading data palette dot marks
kind. Enters and exits at `motion.fast`.
**Overflow:** two lines maximum, then truncation. Toasts stack upward with `s3` (8px) gap
to a maximum of three; further toasts replace the oldest.

#### `inline-error`
Message below the field it belongs to. Text `caption` in `data.rose`, margin above
`s2` (4px). Never a colour alone — always words, because colour alone fails the moment the
screen is photographed or the reader is colourblind.
**Overflow:** wraps to as many lines as it needs. This is the one text part in the
contract that wraps freely.

#### `progress`
Determinate: track as `slider`, filled portion the app accent, with a `mono-data`
percentage trailing. Indeterminate: same track with a moving segment cycling at
`motion.slow`. Height `s1` (2px), radius `r-pill` (999px).

#### `loading`
Occupies the space its content will occupy, so nothing jumps when it resolves.
Background `surface.raised`, radius matching the part being loaded, no spinner for
regions under one second. Longer waits use `progress` with a line of `caption` text
naming what is happening.

#### `tooltip`
Background `surface.overlay`, radius `r-sm` (4px), padding `s3` (8px), text `caption`,
`shadow.overlay`. Appears after a delay, fades at `motion.fast`. Carries the full
text of anything truncated elsewhere.
**Overflow:** wraps at a stated maximum width and never exceeds four lines.

#### `empty-state`
Every empty state is a real screen, not a blank panel: one line in `subhead` naming
the space, and one action as a primary `button`. Centred in its panel with `s6` (24px) gap
between line and action. No apology, no illustration, no "nothing here yet".
This matters most in Prism, whose premise is that content supplies the colour — which
makes its empty screen the weakest surface in the system.


### Navigation

#### `stepper`
Sequential steps across the top of a flow. Step label `label`, gap between steps
`s5` (16px), connector 1px `border.hairline`. Completed steps `text.secondary` with a
`data.jade` mark, current step `text.primary` with the app accent, upcoming steps
`text.muted`.
**Overflow:** more than five steps collapses to a count in `mono-data` plus the
current step's label.

Pagination and breadcrumbs are deliberately absent. Desktop tools scroll rather than
paginate, and these hierarchies are not deep enough to earn a breadcrumb. If a
file-tree-heavy tool ever appears, they are appended as new parts rather than
improvised.

Pagination and breadcrumbs are absent by decision. Desktop tools scroll rather than paginate, and these hierarchies are not deep enough to earn a breadcrumb. If a file-tree-heavy tool appears they are appended as new parts rather than improvised.


---

## 3. What this contract refuses

- No per-screen overrides. Not one gap, not one colour, not once. The escape valve is
  appending a named part in section 4.
- No accent outside the six. They are pre-checked against all six theme bases, so an
  app can change theme without changing its colour identity.
- No second density. Density is set once, here.
- No light mode. Dark is the design, not a setting.
- No painted surfaces — game HUDs, drawing canvases, 3D viewports, terminal panes.
  These need rules of a different kind and are out of scope.
- No chart rules beyond `chart-frame` and the five data hues. What is drawn inside the
  frame belongs to another document.

---

## 4. Additions

Appended parts, newest last. Each names the screen that needed it. Nothing above this
line is ever edited to suit one screen.

| Part | Group | Added | Because |
|---|---|---|---|
| — | — | — | — |

---

## 5. Drift checklist

A screen has drifted if any of these is true.

1. A stock JavaFX control is on screen with its default look.
2. A colour appears that is not in section 1.
3. A gap, padding or margin appears that is not on the spacing scale.
4. A font size appears that is not on the type ramp.
5. A corner radius appears that is not one of the four named radii.
6. Two densities appear in one app.
7. A part is used that is not one of the 38 and was not appended in section 4.
8. A truncation, scroll or collapse happens in a way section 2 does not describe.

Check this file itself with:

```
python3 scripts/check_contract.py /sessions/amazing-intelligent-lamport/mnt/AI-Customer-Service-Agent/STYLE-CONTRACT.md
```

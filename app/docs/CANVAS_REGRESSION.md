# Canvas Scroll & Editing Regression Checklist

Manual verification checklist for scenarios requiring a live Android device / emulator IME, touch gestures, or visual inspection.

## 1. Scrolling & Fling (Short + Long Documents)
- [ ] **Short text (1-3 paragraphs)**: Vertical scroll bounces cleanly at top/bottom with no jitter or unexpected horizontal scroll.
- [ ] **Long text (5,000 / 20,000 / 50,000 words)**: Fast fling and slow finger drag stay fluid with no visual clipping of highlights, spoilers, quote bars, code boxes, dividers, bullets, or checkboxes.
- [ ] **Scroll + mixed media (3-4 images, videos, audio cards)**: Vertical and horizontal scrolling do not glitch, drop media cards, or lose thumbnail/video playback state.
- [ ] **Read-only mode (`reading = true`)**: Scrolling works smoothly; tapping text does not open IME or trigger caret-follow scroll jumps; tapping checkboxes still toggles todo state cleanly.

## 2. Cursor-Follow Scroll (Caret-Follow) & IME
- [ ] **End of 20,000-word note**: Tapping near bottom opens keyboard smoothly and keeps the typing cursor visible above the keyboard + `RichToolbar` (never hidden behind toolbar or IME).
- [ ] **Continuous fast typing / Enter at viewport bottom**: Canvas smoothly scrolls upward to keep ~1.5 lines of breathing room below the caret; rapid keystrokes coalesce without scroll jitter or fighting.
- [ ] **Moving cursor / typing near top edge**: Pressing Enter/Up or tapping lines partially clipped at the top scrolls the canvas down smoothly so the cursor stays visible.
- [ ] **User manual scroll override**: Scrolling manually with finger drag or during an active fling is never overridden or yanked back by caret-follow.
- [ ] **Hindi (Devanagari) & English IME composition**: Swiping/composing Hindi matras/conjuncts and English words commits accurately without cursor jumps or broken spans.

## 3. Formatting, Spans & Selection
- [ ] Bold, Italic, Underline, Strike, Super/Subscript, Text Color, Highlight, Font, Size apply to selection and toggle cleanly for future typing.
- [ ] H1/H2/H3 heading underlines, Quote bars/tint, Code blocks/inline boxes, Dividers, Bullets, Numbered lists (auto-renumbering), and Todo checkboxes align tightly around text while scrolling.
- [ ] Text selection handles & floating Copy/Cut/Paste/Select-All toolbar position cleanly and dismiss cleanly when exiting or opening dialogs.
- [ ] Locked spoiler blocks hide text, mask partial clipboard copies, and prompt passcode on full-locked copy/edit/delete without scrolling glitches.

## 4. Media Drag, Pinch-Zoom, Rotate, Resize, Layers & Horizontal Scroll
- [ ] **No horizontal overflow**: When all media fits inside viewport width, canvas does NOT scroll horizontally.
- [ ] **Media past right edge**: Dragging an image/video/audio card near or beyond right edge expands canvas bounds and enables horizontal scroll cleanly (`canHorizontalScroll`).
- [ ] **Edge auto-scroll during drag**: Dragging media near top/bottom/left/right viewport edges smoothly pans canvas.
- [ ] **Pinch-zoom & rotate**: Selecting an image or video and pinching with two fingers resizes and rotates smoothly; FG/BG layer toggle renders media above or behind writing text accurately.
- [ ] **Diagonal / multi-card drag**: Moving one media card does not reset position or flicker sibling cards or paragraphs.

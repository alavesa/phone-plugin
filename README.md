# Phone

A Paper plugin and resource pack that add a usable phone: right-click it to open a phone screen made of Paper
dialogs. Lock screen with a passcode, a home screen with app icons, a Messages app (speech bubbles, contacts,
scrolling, editing and deleting, unread dot on the item), a Camera app (viewfinder, zoom, front camera, photos and
short videos printed onto maps) and Settings.

- Minecraft Java 26.3, Paper 26.3 (`paper-api:26.3.build.253-stable`), Java 25
- Paper API and Adventure, nothing shaded. One class (`resourcepack/SamePortHttp`) reaches into the server's
  network listener by reflection so the pack can be downloaded through the Minecraft port; see Design decisions
- Pack format 97; post effects only, no vanilla core shader is overridden

| Lock screen | Home | Conversation |
| --- | --- | --- |
| ![Lock](docs/lock.png) | ![Home](docs/home.png) | ![Conversation](docs/conv.png) |

| Contacts | Viewfinder | Front camera photo |
| --- | --- | --- |
| ![Contacts](docs/contacts.png) | ![Viewfinder](docs/cam1.png) | ![Selfie](docs/selfie.png) |

## If the phone shows plain dialogs and the camera has no frame

That is what the phone looks like for a player whose client has **not reported this plugin's pack as loaded**:
plain text screens, no player heads, no bubbles, no viewfinder or zoom effect. Version 1.0.0 served the pack on a
port of its own (8765), which most hosts do not open, so on such a server nobody ever got the pack. Since 1.1.0:

- the pack is downloaded through the Minecraft port itself (`resource-pack.mode: auto`); a 1.0.0 `config.yml`
  with `mode: builtin` is switched to `auto` once, automatically
- a player whose download fails or who declines is told so in chat, and the console says why
- `/phone ui` shows what the server knows about your pack; `/phone ui fancy` forces the textured phone for a player
  who installed the pack by hand; `assume-loaded: true` does that for everyone (merged server packs)
- behind a Velocity/Bungee proxy the Minecraft port cannot serve files: use `mode: builtin` with an open port, or `url`

## What was checked, and what was not

Checked on a real 26.3 client against a Paper 26.3 server (screenshots above come from those runs):

- the plugin loads cleanly over a 1.0.0 data folder (config, language file and database are migrated)
- the pack arrives through the Minecraft port and loads
- lock screen (set-up), home with app icons and unread badge, contacts, conversation, photo/video result screens;
  the plain-text versions of the 1.0 screens
- unread dot on the item; unread count going down and staying down over a restart
- viewfinder, zoom post effects, the photo matching the framed square, printing, the photo surviving a restart
- front camera: the photographer as a posed, textured figure (stand-in skin: the test server is offline-mode)
- video: recording with a panning camera, playing on the held map, frames loaded only while held (0 in the
  inventory, 24 in the hand, 0 again five seconds after putting it away)
- textures folder: a custom `phone.png` ends up in the pack and gets its red dot

**Not checked** (the test client could not be given mouse or keyboard input, and there was only one client):

- clicking any dialog button: entering a passcode, sending, scrolling, editing, deleting, Escape
- live delivery between two online players, auto-refresh of an open conversation, the notification hint
- how the sounds sound
- mouse-wheel zoom, left-click (front camera), F (video) and sneak in the viewfinder; they were driven by command
- downloaded skins, and every pose except standing (walking, sneaking, swimming, gliding, sleeping, sitting)
- the FOV fallback zoom, photos and videos in item frames, the crafting recipe, mute/block/rate limit

Treat those as untested code until you have clicked through the checklist at the end.

## Setup

1. `./gradlew build` (needs a JDK 25 or newer). Output: `build/libs/phone-1.0.0.jar` and `build/pack/phone-pack.zip`
   with its SHA-1 in `build/pack/phone-pack.zip.sha1`.
2. Put the jar in `plugins/` and start the server. `plugins/Phone/` gets `config.yml`, `lang/en.yml`, `phone.db`,
   `photos/` and `phone-pack.zip`.
3. Choose how players get the pack (next section), then `/phone give <player>`.

Only when you change the pack's art or geometry: `python3 tools/gen_pack.py` regenerates textures, the `phone:ui`
font, the zoom post effects, the sounds (needs `ffmpeg`) and `src/main/resources/font/glyphs.properties`.
`python3 tools/gen_widths.py <client.jar>` rebuilds the default-font width table from a vanilla client jar.
`python3 tools/validate_shaders.py <glslang> resourcepack/assets resourcepack/assets/phone/shaders/post/*.fsh`
compiles the shaders the way the 26.3 client does (SPIR-V).

## Resource pack hosting

`resource-pack.mode` in `config.yml`:

| mode | what happens |
| --- | --- |
| `auto` (default) | `same-port`; if the Minecraft port cannot be hooked, `builtin`. |
| `same-port` | The client downloads the pack from the Minecraft port. Nothing else to open. Not behind a proxy. |
| `builtin` | The plugin serves `phone-pack.zip` over HTTP on `builtin.port` (8765). Open that TCP port. |
| `url` | You upload `plugins/Phone/phone-pack.zip` to a host that serves the file directly and put the link in `resource-pack.url`. The plugin sends that link with the SHA-1 of its own copy, so upload the file again after every plugin update. |
| `none` | Nothing is sent. Use this when you merge `resourcepack/` into your server pack, and set `assume-loaded: true`. |

The link sent to a player uses the address they typed to join, or `builtin.public-host` if set. The pack is sent
on join with the Paper/Adventure resource pack API, `replace(false)`, so other packs stay. `required` and `prompt`
are configurable. `/phone pack` (or Settings on the phone) sends it again.

### Changing textures

`plugins/Phone/textures/` takes `phone.png`, `phone_unread.png`, `photo.png` and `photo_paper.png` (square PNGs;
the built-in ones are in `textures/default/` as a starting point). With only `phone.png` the red dot is drawn onto
it for the unread look. Anything else in the pack (app icons, bubbles, sounds, shaders) is replaced by a file with
the same path under `plugins/Phone/pack/`. `/phone reload` rebuilds the pack and sends it to everyone online. If
the phone's model lives in another pack, point `item.models` in `config.yml` at it.

Without the pack the phone still works: screens are plain text, sounds fall back to vanilla ones, zoom uses the
FOV method. One thing does not degrade by itself: the item's look is an `item_model` component, which is the same
for everyone, so players without the pack see the missing-model cube. If many of your players play without the
pack, set `item.use-item-model: false` (the phone then looks like its base item and unread shows as a glint).

## Commands and permissions

| command | permission | |
| --- | --- | --- |
| `/phone` | `phone.use` | open your phone (you must carry one) |
| `/phone give [player] [phone\|paper [amount]]` | `phone.give` | give a phone or photo paper |
| `/phone block <name>`, `/phone unblock <name>` | `phone.message` | your own block list (also a button in every conversation) |
| `/phone fov <30-110\|reset>` | `phone.camera` | tell the camera your FOV setting so photos match your screen |
| `/phone pack` | `phone.use` | send the resource pack again |
| `/phone ui [auto\|fancy\|plain]` | `phone.use` | show or choose textured / plain-text screens |
| `/phone open <player> [home\|messages\|camera\|chat <name>]` | `phone.admin` | open a screen for a player, skipping their passcode |
| `/phone shutter <player> [print\|video\|selfie\|save]` | `phone.admin` | work the camera of a player in the viewfinder |
| `/phone passcode reset <name>` | `phone.admin` | remove a forgotten passcode |
| `/phone status` | `phone.admin` | pack delivery, SHA-1, video frames in memory |
| `/phone mute <name> [minutes]`, `/phone unmute <name>` | `phone.admin` | stop a player from sending |
| `/phone reload` | `phone.admin` | reload config and language |

`phone.use`, `phone.message`, `phone.camera` default to everyone; `phone.give` and `phone.admin` to operators.

## How it works

### The item

A phone is any item with the `phone:phone` tag in its persistent data. Its look is the `item_model` component
pointing at one of three item model definitions in the pack: `phone:phone`, `phone:phone_unread` (red pixel dot,
top right) and `phone:phone_camera` (a `select` on `display_context` that draws nothing in first person, so the
phone does not cover the viewfinder). The plugin sets the state on every phone the player carries when a message
arrives, when the last unread message is read, on join, on pickup, and in a slow pass every two seconds. Messages
belong to the player's UUID: a stolen phone shows the thief's messages; a dropped phone loses its dot.

### Lock screen and passcode

With `passcode.enabled` (default) a player chooses a 4-digit passcode the first time they open a phone and enters
it every time after that; the apps appear after signing in. The phone locks again when its screen closes. Five
wrong codes lock it for 30 seconds (both configurable). The code is changed in Settings and belongs to the player,
like the messages: it is stored as a salted SHA-256 hash, which keeps it out of plain sight but, with only 10 000
possible codes, would not stop someone who has the database file.

The code is typed into a dialog text field. Dialogs have no password field, so the digits are visible while typed.

### The screens

Every screen is one multi-action dialog whose body is a single multi-line text. With the pack, each line is built
by `ui/Line`: glyphs of the `phone:ui` font (bitmap providers plus a `space` provider with advances of ±1 … ±512)
are placed at absolute pixel positions, background first, text last. An app icon on the home screen is a glyph in
the label of an ordinary dialog button: it covers the button's grey face with a dark label pill and stands 34 px
above the button with the round icon (the client does not clip a label that fits its button). The button's border
stays and lights up on hover. A speech bubble row is a left cap, tiles of
128/64/…/1 px that add up to the text width, and a right cap; the pieces are white and take the bubble colour from
the text colour. The panel behind everything is one tall glyph on the first line.

Text is measured on the server with `font/default_widths.tsv` (2 400 characters, generated from the vanilla jar):
ASCII, Latin-1 including ä ö å, Greek, Cyrillic. Bold is exact (+1 px per character). Characters outside the table
(CJK, emoji) are drawn by the client's unifont fallback and are measured at 9 px, so their bubbles can be a few
pixels too wide. Private-use characters and `§` codes are stripped from typed text.

Buttons are custom click actions `phone:<path>`; the client returns them with the input values and
`UiController.onClick` routes them. Nothing is stored per dialog.

### Post effects

All three are added and removed per player with `Player.postEffects()`.

| effect | shader | what it does | when |
| --- | --- | --- | --- |
| `phone:screen` | `post/screen.fsh` | cool tint and vignette over the world behind the dialog | while a phone screen is open |
| `phone:camera_<zoom>` | `post/camera.fsh` | magnifies the centre of the frame, dims everything outside the photo square, draws brackets, thirds and zoom pips | while the viewfinder is open; one JSON per zoom level |
| `phone:flash` | `post/screen.fsh` | white flash | 3 ticks after the shutter |

Post effects run on the 3D frame before the HUD, so they cannot draw into the dialog or over the hotbar.

### Zoom

Wheel up or down in the viewfinder changes the zoom level (the slot change is cancelled, the phone stays in hand).

- **Primary: post effect.** Robust and server-driven through a first-class API; no entity or HUD trick is needed
  to trigger it. Cost: it is a digital zoom, so the picture gets blurry at 8x and above, and the vanilla
  crosshair is not zoomed.
- **Fallback: FOV.** A movement-speed modifier makes the client narrow its own FOV. It tops out near 2x, roots
  the player, and does nothing if the client has "FOV Effects" at 0. Used when `camera.zoom-method: fov`, and
  automatically for players without the pack.

The photo is rendered with the zoom the method actually gives, so it matches the frame.

In a conversation, Scroll up / Scroll down move the window by `ui.scroll-step` messages; a window holds as many
messages as fit `ui.max-lines-per-page`. Edit lists your latest messages to that person; picking one opens it in a
text field with Save and Delete. An edited message is marked "edited"; a deleted one is gone for both sides. Both
can be switched off or limited to recent messages (`messaging.allow-edit`, `allow-delete`, `edit-window-minutes`),
and edits fire `PhoneMessageEvent` with `isEdit()`.

### Photos

A server cannot see a client's screen, so the photo is rendered on the server. On the shutter:

1. Main thread, same tick: the eye position and direction, world time and weather, and up to 64 entity boxes are noted.
2. Main thread, next ticks: the chunks the view can reach are copied as `ChunkSnapshot`s, `chunks-per-tick` (6) at a
   time, at most `max-chunks` (120). Unloaded chunks are skipped, never loaded.
3. One background thread: 128×128 rays walk the snapshots voxel by voxel up to `max-distance` (96). Colour is the
   block's map colour, shaded by face, stored light and time of day; water is looked through with depth tint;
   glass is transparent; mobs are two-colour boxes; the distance fades into a sky with sun, moon and stars.
   **Players** are the vanilla player model, six boxes textured from their skin (downloaded once per player, in
   the background, from the address in their profile), posed from what the server knows: where they look, sneaking,
   swimming, gliding, sleeping, sitting in a vehicle, an item held up, and a walk cycle while they move.
4. Main thread: the result screen opens with Print and Retake.

Cost: the main thread only pays for the snapshot copies. The render is at most about 3 million block lookups and
is queued, so several photographers share one core. A cooldown (5 s) applies per player.

Print makes a **filled map**: it shows in hand and in item frames with no client support, and a custom item could
not show a picture at all. The image is saved as `plugins/Phone/photos/<map id>.png` and indexed in `phone.db`;
when the server loads the map again the plugin repaints it from the file. Name and lore carry photographer, date
and (optionally) location. `require-photo-paper` consumes a Photo Paper item outside creative mode; a full
inventory drops the photo at your feet or refuses, per config.

Left-click in the viewfinder switches to the **front camera**: the picture is taken from arm's length in front of
your face, looking back at you. The viewfinder keeps showing the rear view (the client decides what is on screen;
press F5 to see roughly what the front camera sees).

### Video

The swap-hands key (F) starts and stops a recording, up to `camera.video.max-seconds` (6) at `fps` (4). The chunks
within `max-distance` (48) are copied once; after that each frame only notes the camera and everything alive, and
is rendered on the background thread. So blocks stand still in a video and players and mobs move.

A saved video is a map like a photo. It plays in a loop while someone holds it (or has it in an item frame in
view). Its frames live in `plugins/Phone/videos/<map id>.bin` and are in memory only while it is being watched:
five seconds after the last look they are dropped, and a video lying in an inventory never loads them.
`/phone status` shows how many frames are in memory.

### Messages

SQLite (`plugins/Phone/phone.db`) on one background thread; every Bukkit call happens on the main thread. A
message to an offline player simply waits unread and is announced when they join. `PhoneMessageEvent` (cancellable,
text editable) fires before a message is stored. Moderation: per-player block list, admin mute, rate limit,
maximum length.

## Known limitations

- **Dialogs cannot be updated in place.** Showing a new message means sending the whole dialog again, which empties
  the text field. Every button on the conversation screen hands the typed text back and it is restored; a refresh
  the player did not ask for cannot. `messaging.auto-refresh: false` turns those off (sound only, press Refresh).
- **The server is not told when a dialog closes**, except that Escape runs the exit action. So every screen has one
  and Escape goes back one screen at a time.
- **The client clips the dialog body at the last button.** The phone panel therefore ends flat under the buttons
  instead of with its rounded bottom edge, and on a small window a long conversation scrolls inside the dialog.
- The dialog title row and its warning icon are vanilla and cannot be removed.
- Buttons are vanilla widgets. App icons are drawn over them; the other buttons (Send, Scroll, Back ...) keep the
  vanilla look, because a pack can only retexture every button in the game at once.
- What the server does not know about a player is not in the picture: the exact step of the walk cycle, attack
  swings, held items, armour, capes and the second skin layer. Mobs are still boxes.
- A video is 128x128 at a few frames per second, blocks do not change in it, and it has no sound.
- The viewfinder cannot show the front camera's view.
- The FOV of the client is unknown to the server: photos assume 70 until the player runs `/phone fov`.
- Photos are block-colour renders, not screenshots: no block textures, no block shapes (stairs and fences are full
  cubes), flowers are green columns, particles and item frames are missing.
- Zoom pips show the position among the pack's eight levels, not among the levels chosen in config.
- Every printed photo uses one map id; ids are a finite, world-wide resource.
- Player heads of offline players depend on the client being able to resolve the profile.
- If two packs override the same vanilla file only one wins; this pack overrides none.

## Design decisions and trade-offs

- **Post effects instead of core shaders.** The request allowed either. On 26.3 one core shader that fails to
  compile makes the client drop every server pack, and two packs cannot both override `core/text`. Post effects
  have their own ids, cannot collide, and have a server API.
- **One multi-line text per screen** rather than one body element per bubble: elements are spaced 10 px apart by
  the client, lines of one text are exactly 9 px apart, so bubble rows can tile.
- **Lines padded to the panel's width.** The dialog text widget counts lines by wrapping at the width of its
  widest finished line, measured from the line start; if the cursor ever travels past that, it reserves empty
  lines. Found on the client, documented in `ui/Line`.
- **Key-based click actions** instead of callbacks: no per-dialog state on the server, nothing expires.
- **Hierarchical Escape** (back, then close) because it is the only close signal there is.
- **Filled maps for photos**: the only vanilla item that can show an arbitrary image.
- **SQLite over JSON**: conversations are read by pair, by page and by unread state, and written while people play.
- **The pack comes through the Minecraft port.** 1.0.0 avoided this because it needs the server's Netty listener,
  which is not Paper API; in practice a second open port is exactly what game hosts do not give you, and a phone
  without its pack is not the product. The hook is one class, found by reflection on field types, passes on
  every request that is not its own, and falls back to the separate port when it cannot be installed.
- **Passcode in a text field, not a keypad of buttons.** Every button press replaces the whole dialog; a keypad
  would redraw the screen four times per sign-in.
- **Videos reuse the photo renderer over frozen chunks.** Copying chunks every frame would put the cost on the
  main thread; freezing them keeps a recording as cheap per tick as standing still.

## Layout

```
src/main/java/fi/alavesa/phone/
  PhonePlugin            wiring, reload, recipe
  phone/                 PhoneItems, PhoneListener (use, item state), PhoneCommand
  ui/                    UiController (screens, routing), ScreenRenderer + Fancy/PlainRenderer, Line, Glyphs,
                         FontWidths, TextWrapper, Views
  messaging/             MessageService, PhoneMessageEvent, RateLimiter, records
  camera/                CameraService (viewfinder, zoom, photo, video), RayTracer, Scene, Figure (player model),
                         SkinCache, PhotoStore (photo and video maps)
  storage/               Database, PlayerSettings (FOV, UI mode, passcode)
  resourcepack/          PackService (build, deliver, who has it), SamePortHttp, PackHttpServer
  config/                PhoneConfig, Lang
  util/                  PostFx, Sounds, Tasks
resourcepack/
  pack.mcmeta
  assets/phone/items/         phone, phone_unread, phone_camera, photo, photo_paper
  assets/phone/models/item/   phone, phone_unread, photo, photo_paper
  assets/phone/textures/item/ the four 32x32 textures
  assets/phone/font/ui.json   bubbles, panels, header, app tiles, badges, big clock digits, marks, spaces
  assets/phone/textures/font/ bubble_{top,mid,bot,one}, panel_<height> (14), header, apps, badges, icons, marks
  assets/phone/post_effect/   screen, flash, camera_{1,2,3,4,6,8,12,16}
  assets/phone/shaders/post/  screen.fsh, camera.fsh
  assets/phone/sounds.json, sounds/phone/{notify,send,shutter}.ogg, lang/en_us.json
tools/                   gen_pack.py, gen_widths.py, pnglib.py, validate_shaders.py
```

The sounds are synthesised placeholders; replace the `.ogg` files with your own under the same names.

## Testing checklist

Two players, A and B, both with a phone.

- [ ] Pack prompt appears on join; after accepting, the phone item shows its texture
- [ ] `/phone ui` says "textured"; if not, the console says why the pack did not load
- [ ] First right-click asks for a new passcode (rejects 3 digits, letters, two different codes), then shows Home
- [ ] Close and open again: lock screen with the time; wrong code counts down, the fifth locks for 30 s; right code opens
- [ ] Settings: change passcode (wrong old code refused); `/phone passcode reset` makes the phone ask for a new one
- [ ] Home: three round icons, hover lights the label's border, the Messages badge shows the unread count
- [ ] Right-click (air, block, entity; main and off hand) opens the phone and does nothing else
- [ ] Messages → B → type → Send: bubble appears on the right, A hears the send sound
- [ ] B hears the notification, sees the hint, B's phone gets the red dot wherever it is in the inventory
- [ ] B opens the conversation: dot and badge disappear; A's next refresh shows the double tick
- [ ] Both in the conversation: an incoming message redraws the screen about a second later
- [ ] Type without sending, press Refresh / Older / Block: the text is still in the field
- [ ] A long message wraps inside its bubble; ä ö å align; an emoji does not break the layout
- [ ] A long history: Scroll up / Scroll down move three messages at a time, typed text survives
- [ ] Edit: pick a message, change it, Save: both sides see the new text marked "edited"
- [ ] Edit: Delete removes it for both sides; an unread deleted message also clears the other side's dot
- [ ] Message B while B is offline: B gets the dot and the hint on join; survives a server restart
- [ ] Search finds an offline player by exact name; Block stops their messages; `/phone mute` and the rate limit refuse with a notice
- [ ] Escape goes Conversation → Contacts → Home → closed, and the blue tint goes away
- [ ] Decline the pack: all screens readable as plain text, vanilla sounds
- [ ] Camera: frame appears, phone hidden in first person, wheel zooms, sneak closes, cooldown message
- [ ] Left-click: "Selfie" in the action bar; the photo shows you with your skin, facing the camera
- [ ] Photograph another player standing, walking, sneaking, swimming, in a boat, in a bed, with elytra
- [ ] F starts a video, F stops it (or it stops by itself); Save video; hold it: it plays; a walking player moves in it
- [ ] `/phone status` shows frames while the video is held and 0 a few seconds after it is put away
- [ ] `plugins/Phone/textures/phone.png` + `/phone reload`: everyone gets the new phone look
- [ ] The photo shows what was inside the square, at every zoom level
- [ ] Print: item has name and lore; shows in hand and in an item frame; still there after a restart
- [ ] Full inventory when printing; `require-photo-paper` in survival and in creative
- [ ] Drop the phone with a dialog or the viewfinder open; hold two phones; give your phone to someone else
- [ ] `/phone reload` with a screen open; `/reload`-free restart keeps mutes, blocks and unread counts

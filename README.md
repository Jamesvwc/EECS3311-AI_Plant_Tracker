# EECS3311 - AI Plant Tracker
This project uses an AI agent to efficiently track all of the plants a user may have, giving updates on when to water them, providing information about best care practices, and overall ensuring the user can easily keep up with the state of their plants.

## Prerequisites
- Java 21 or newer (`java -version`)
- No Maven install needed: the Maven wrapper (`./mvnw`) downloads Maven and all dependencies on first run (and checks for installation for any subsequent runs, so it doesn't reinstall each time)
- API keys (all have free tiers):
  - Pl@ntNet: https://my.plantnet.org (plant identification from photos)
  - Perenual: https://perenual.com/docs/api (plant care database)
  - Cohere: https://dashboard.cohere.com/api-keys (LLM used by the agent; also reads photos)

## Local configuration (API keys)
Real keys are never committed. They live in a git-ignored `.env` file. When testing, write your own keys into the .env file by replacing the placeholders.

## Run the app (GUI)
```bash
./mvnw spring-boot:run
```

Open http://localhost:8080. Check that your keys were picked up at http://localhost:8080/api/status (it shows only true/false for each key, never the key itself).

The GUI also has a chat box: the round **+** button in the bottom-right corner of every page (or **Chat** in the header) lets you talk to the AI agent in plain language. You may also attach photos.

The chat box never changes anything on its own. Questions and checks (identifying a plant, health checks, recommendations, "what needs water?") are answered straight away. When you ask for a change, such as "I watered the fern", the agent replies with exactly what it will do and a **Yes** button. Nothing changes until you press **Yes**. Typing "yes" only shows the buttons again, and **No** (or typing "no") cancels the change. This covers adding plants, schedule changes and undo, watering, reminders, moving plants, care notes, notes, and what the agent remembers about you.

## Run the agent from a terminal (Agent.jar)
`Agent.jar` is a second front end for the same agent. It talks to the running server, so start the server first (above), then in a second terminal:
```bash
./mvnw package -DskipTests
```
```bash
java -jar target/Agent.jar
```

Options: `--user <id>` (default `1`, the default user) and `--server <url>` (default `http://localhost:8080`), e.g. `java -jar target/Agent.jar --user 2`. If no server answers, it says so and exits.

Inside, every command has the form `/noun verb <required values> --optional-things`. Values with spaces go in quotes. Type `/help` for the full list, or `/help plants add` for one command. Some examples:
```text
/identify photo samplephotos/monstera.jpg     (up to 5 photos; prints the top 3 matches with confidence)
/identify select 1                            (pick a match, see its care notes)
/plants add "Monstera deliciosa" --nickname "Monty"
/plants list                                  (grouped by species, with how many you own and their IDs)
/plants water P3                              (P = plant ID; names work too: /plants water "Monty")
/health check Monty --describe "yellow spots on the leaves" --photo leaf.jpg
/calendar update "I work 9-5 on weekdays, away Dec 20-27"
/recommend ask "low maintenance and safe for cats"
/chat "how often should I water a snake plant?"
/quit
```

- **IDs:** `P` = an owned plant, `W` = a wishlist item, `R` = a recommendation.
- **Confirmation:** any command that adds or deletes something asks `[y/N]` first.
- **`/chat` is read-only.** It answers questions. If you ask it to change something (e.g. "I watered the fern"), it suggests the matching command (`/plants water P5`) and runs it only if you confirm.
- **Mistyped commands:** a mistyped command shows its usage, and the agent suggests the command it thinks you meant.

Keys at the prompt (in a terminal; piped-in input is read as plain lines):

| Key | What it does |
|---|---|
| ← → | Move within the line. Home and End jump to its start and end (Ctrl+E also goes to the end). |
| Ctrl+A | Highlights the whole line. Ctrl+C copies it, typing replaces it, Backspace clears it, ← or → go to its start or end. |
| Ctrl+C | Copies the highlighted line to your computer's clipboard. On an empty line it quits, like `/quit`; on a line that isn't highlighted it does nothing. While a command is still running, it quits. |
| Ctrl+V | Pastes from the clipboard, replacing the highlighted line if there is one. On a Mac, Cmd+V pastes too. |
| ↑ ↓ | Go through earlier commands. They are saved in `~/.plant-organizer/agent_history`, so they are still there next time. Answers to `[y/N]` are not saved. |
| Tab | Completes commands, verbs, options and values: your plants (IDs, or names once you start typing one), wishlist items, recommendations, rooms and spots, friends, users, photo files, and fixed choices such as `yes`/`no`. When several fit, it lists them. Values with spaces get quotes. |
| Ctrl+D on an empty line | Quits, like `/quit`. |

On a Mac, Cmd+C copies text you select with the mouse. The Terminal app keeps Cmd+C for itself and never passes it to Agent.jar, so Cmd+C can't copy the Ctrl+A highlight; use Ctrl+C for that. Both copy to the same clipboard.

## Run the tests
```bash
./mvnw test
```

The tests replace Pl@ntNet, Perenual, Cohere and Wikipedia with local stub servers, so they need no keys, no network and use no quota.

## Users (no login)
Login is out of scope, so the browser chooses which user it acts as. A default user "Me" is created on first start. **Account > My Profile** lets you create more users and switch between them, which is how friends (F11) can be tried out.

## Features and example usage

| Feature | Where | Try this |
|---|---|---|
| F01 Plant Identifier | Plant Identification page, or chat | Upload a photo, or type a description ("big glossy leaves with holes"), pick a match, then **I own this plant** or **Add to wishlist**. For a description, the agent suggests species and each match shows a reference photo from Wikipedia. Chat: attach a photo and send. |
| F02 Watering Schedule Generator | Happens when a plant is added; My Plants > Details > Schedule | Add two plants with similar needs: the second is grouped with the first. If care data is thin, press **Search for more watering info**. |
| F03 Watering Schedule Updater | Calendar page, or chat | "I work 9-5 on weekdays, need 30 minutes to get home, away Dec 20-27", or upload a timetable photo or a calendar `.ics` export. **Undo last change** reverses it. |
| F04 Watering Notifications | Bell icon in the header; Calendar check-in; per-plant toggle | Answer the agent's "Would you like reminders?" question, then the bell shows due and missed waterings. Mark plants as watered there or in chat ("I watered the fern"). |
| F05 Plant Health Analyzer | My Plants > Details > Health, or chat | Describe a problem and/or add a photo. **Not satisfied? Dig deeper** searches the web. Save results to the plant's notes. |
| F06 Automatic Plant Recommender | My Plants > Recommendations | The list refreshes when your plants change. Sort by ease of care, name or space; **Unrecommend** teaches the agent what you don't like. |
| F07 New Plant Recommender | Recommendations page, or chat | "Recommend a low-maintenance plant that's safe for cats". Keep the ones you like. |
| F08 List of Owned Plants | My Plants > Current Plants | Edit care notes (empty fields are ones the agent couldn't find), add notes, delete plants. |
| F09 List of Desired Plants | My Plants > Wishlist | Pick where you'd put a plant; the agent says whether that spot suits it. **I bought it** moves it to your plants. |
| F10 Room Builder | My Plants > Plant Spaces | Build rooms and spots, place plants, select any space to list every plant inside it, drag spaces on the floor plan. Chat: "my snake plant is on the bedroom shelf". |
| F11 Plant Sharing with Friends | Account > Friends & Sharing | Add a friend, tick **can see my plants**; they then see your plant names only. |
| F12 Watering Schedule Sharer | Account > Friends & Sharing | Create a link for chosen plants; anyone with the link sees watering and sunlight needs (no account needed). |

## REST endpoints (used by the GUI and the chat box)
All requests carry the `X-User-Id` header (default 1).

- `POST /api/identify` (multipart `photos`), `POST /api/identify/text`, `POST /api/identify/confirm`
- `GET/POST /api/plants`, `GET/PUT/DELETE /api/plants/{id}`, `/notes`, `/water`, `/notifications`, `/schedule`, `/schedule/research`, `/health`
- `GET /api/calendar?from&to`, `GET /api/calendar/overview`, `POST /api/calendar/availability` (multipart `text` or `file`), `POST /api/calendar/undo`
- `GET /api/notifications`, `POST /api/notifications/read`, `POST /api/notifications/default`
- `GET /api/recommendations?sort=CARE|NAME|SPACE`, `POST /api/recommendations/refresh`, `POST /api/recommendations/request`, `POST /api/recommendations/{id}/accept`, `DELETE /api/recommendations/{id}`
- `GET/POST /api/desired`, `PUT/DELETE /api/desired/{id}`, `POST /api/desired/{id}/fit`, `/research`, `/buy`
- `GET/POST /api/spaces`, `GET/PUT/DELETE /api/spaces/{id}`, `GET /api/spaces/{id}/plants`, `POST /api/spaces/{id}/plants/{plantId}`
- `GET/POST /api/friends`, `PUT /api/friends/{id}/can-view`, `GET /api/friends/{id}/plants`
- `POST/GET /api/share`, `GET /api/share/{token}` (public), `DELETE /api/share/{token}`
- `POST /api/chat` (multipart `message`, optional `photo`), `POST /api/chat/action` (button presses; `APPROVE`/`DECLINE` answer a change the agent proposed), `GET/DELETE /api/chat/history`
- Used by Agent.jar: `POST /api/chat/ask` (read-only chat: answers questions; for a change it returns a `suggestion` with the intent and its arguments instead of doing it), `POST /api/chat/interpret` (what does this text ask for? runs and stores nothing)
- `GET/POST /api/users`, `GET /api/users/me`

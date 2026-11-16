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

## Run the app (GUI + chat CLI)
```bash
./mvnw spring-boot:run
```

Open http://localhost:8080. Check that your keys were picked up at http://localhost:8080/api/status (it shows only true/false for each key, never the key itself).

The **CLI is the chat box**: the round **+** button in the bottom-right corner of every page (or **Chat** in the header) lets you talk to the AI agent in plain language. You may also attach photos.

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
| F01 Plant Identifier | Plant Identification page, or chat | Upload a photo, or type a description ("big glossy leaves with holes"), pick a match, then **I own this plant** or **Add to wishlist**. Chat: attach a photo and send. |
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
- `POST /api/chat` (multipart `message`, optional `photo`), `POST /api/chat/action`, `GET/DELETE /api/chat/history`
- `GET/POST /api/users`, `GET /api/users/me`

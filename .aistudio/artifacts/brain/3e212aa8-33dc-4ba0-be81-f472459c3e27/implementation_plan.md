# Mandatory Google Account Verification & Sign-In Gate

Enforce Google Account verification on launch for MT Explorer before users can access the dual-pane file management interface, providing a secure, cloud-synced entry point using Jetpack Credential Manager and Firebase Authentication.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following user preferences were confirmed in the interactive interview:
> - **Presentation Model**: **Full Login Screen Before Accessing Files** (mandatory authentication gate on app start).
> - **Account Verification**: **Any Valid Verified Google Account** (authenticated via Google Identity Services and Firebase Auth).
> - **Session Persistence**: Automatic silent sign-in on subsequent launches (`GetGoogleIdOption`) with an interactive "Sign in with Google" button (`GetSignInWithGoogleOption`) on cold launch or when signed out.

---

### 1. Overview & Core Concept

- **What It Does**: Gates application entry behind a verified Google account. Users must authenticate with Google before reaching the dual-pane file browser. Once verified, the user's identity is linked with Firebase Firestore to automatically sync bookmarks, preferences, and session state.
- **Target Audience**: Users seeking a secure, personalized file manager experience where access is gated behind Google credentials and bookmarks are safely persisted.
- **Key Value**: Guarantees zero unauthenticated file tampering, persistent per-user cloud profiles, and seamless single-tap authentication without passwords or manual account creation.

---

### 2. User Experience & Visual Design

#### Key User Flows
1. **Cold Launch & Auto-Detection**:
   - On app startup, MT Explorer checks for an active Firebase user session or saved Google credentials via `GetGoogleIdOption`.
   - If an active session exists, the app transitions smoothly directly into `BrowserScreen`.
2. **Branded Sign-In Screen (`SignInScreen`)**:
   - If unauthenticated, the user is presented with a full-screen entry portal featuring the MT Explorer emblem, high-tech cyan/teal atmosphere, and a list of key capabilities:
     - 📁 **Dual-Pane File Management**: Side-by-side or tabbed high-speed operations.
     - ☁️ **Cloud Bookmark Sync**: Sync folders and paths across all your devices.
     - 🛡️ **Verified Security**: Safe streaming transfers with zero partial file corruption.
   - Prominent **"Sign in with Google"** button with responsive feedback, a loading spinner during token exchange, and clear error surfacing.
3. **Post-Authentication Transition**:
   - Upon successful token exchange and Firebase authentication, `MainActivity` switches from `SignInScreen` to `BrowserScreen` using an animated crossfade.
4. **Sign Out Flow**:
   - Within `BrowserScreen` (via the Storage Roots / Account menu), tapping "Sign Out" clears the Credential Manager state, terminates the Firebase session, and returns the user to `SignInScreen`.

#### Visual Identity & Styling
- **Theme**: Premium Tech Dark / AMOLED (`#0B0F14`) consistent with MT Explorer.
- **Accent**: Electric Cyan (`#00D2FF`) for primary buttons and focus states, with warm steel borders (`#1B2430`).
- **Typography**: Bold display headings paired with clean subheadings and readable status captions.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Composable Auth Gate vs. Navigation Route**:
  - *Chosen Approach*: Root Composable Gate in `MainActivity` (`if (currentUser == null) SignInScreen(...) else BrowserScreen(...)`).
  - *Why*: Prevents `FileManagerViewModel` from initializing file watchers, storage queries, and Firestore listeners when unauthenticated, strictly enforcing zero-trust data access.
- **Decision 2: Credential Manager Option Exclusivity**:
  - *Chosen Approach*: Maintain separate requests for interactive sign-in (`GetSignInWithGoogleOption`) and silent auto sign-in (`GetGoogleIdOption`).
  - *Why*: Ensures maximum compatibility with Android 14–16 (API 34–36) bottom sheet responsiveness without UI freezes or cancellation bugs.

---

### 4. Technical Architecture & Data Strategy

#### System Architecture

```
┌────────────────────────────────────────────────────────┐
│                      MainActivity                      │
└───────────────────────────┬────────────────────────────┘
                            │
               AuthStateListener (currentUser)
                            │
           ┌────────────────┴────────────────┐
           ▼                                 ▼
   [currentUser == null]             [currentUser != null]
┌────────────────────────┐         ┌────────────────────────┐
│      SignInScreen      │         │     BrowserScreen      │
│  - Branding Banner     │         │  - Dual-Pane Browser   │
│  - Features List       │         │  - Storage Roots       │
│  - "Sign in with       │         │  - Cloud Bookmarks     │
│     Google" Button     │         │  - Sign-Out Affordance │
└──────────┬─────────────┘         └────────────────────────┘
           │
           ▼
┌────────────────────────┐
│      AuthManager       │
│  - CredentialManager   │
│  - Firebase.auth       │
└────────────────────────┘
```

#### File Changes
- **New File**: `feature/auth/ui/SignInScreen.kt` — Full-page interactive sign-in portal with Google button and feature highlights.
- **Modified**: `MainActivity.kt` — Implement root auth gate switching between `SignInScreen` and `BrowserScreen` based on `viewModel.currentUser`.
- **Modified**: `core/firebase/AuthManager.kt` & `FileManagerViewModel.kt` — Ensure clean callback propagation and immediate state reset on cancellation or sign-out.

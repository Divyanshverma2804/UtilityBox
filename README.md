# UtilityBox

UtilityBox is an open‑source Android productivity utility that helps users capture on‑screen content, extract text, and manage clipboard history efficiently through a floating overlay widget.

The goal of this project is to make everyday copy–paste, text extraction, and quick actions faster and more accessible without constantly switching between apps.

---

## ✨ Features

### 📸 Rectangular Screenshot Tool

* Capture a selected rectangular region of the screen
* Useful for grabbing specific UI elements, messages, or content

### 📝 On‑Screen Text Extraction (OCR)

* Extract text directly from screenshots or on‑screen content
* Copy extracted text instantly for reuse

### 📋 Smart Clipboard History

* Automatically stores copied text
* Saves text extracted via OCR
* Allows reuse of previous clipboard items
* Clipboard data is stored locally on the device

### 🧩 Floating Overlay Widget

* Always‑accessible overlay for quick actions
* Works on top of other apps
* Designed to minimize disruption to the user workflow

---

## 🔐 Permissions Used

UtilityBox requires the following permissions to function correctly:

* **Accessibility Service**

  * Used for advanced clipboard handling and overlay interaction
  * No user data is sent outside the device

* **Screen Capture Permission**

  * Required for screenshot capture and OCR functionality

> ⚠️ UtilityBox does **not** collect or transmit personal data. All clipboard and OCR data remains on‑device.

---

## 📦 Installation

### Option 1: GitHub Release APK

1. Go to the **Releases** section of this repository
2. Download the latest signed APK
3. Install it on your Android device (allow unknown sources if prompted)

### Option 2: Build from Source

```bash
git clone https://github.com/Divyanshverma2804/UtilityBox.git
cd UtilityBox
```

Open the project in **Android Studio**, sync Gradle, and run on a device.

---

## 🚀 Getting Started

1. Install the app
2. Enable the **Accessibility Service** when prompted
3. Grant screen capture permission when using screenshots or OCR
4. Use the floating overlay to access clipboard, OCR, and screenshot tools

---

## 🧠 Use Cases

* Quickly copy text from apps that don’t allow text selection
* Extract text from images, videos, or UI screens
* Reuse frequently copied content via clipboard history
* Improve productivity during studying or content creation

---

## 🤝 Contributing

Contributions are welcome and encouraged.

### How to Contribute

1. Fork the repository
2. Create a new feature or fix branch
3. Make focused, well‑documented changes
4. Open a Pull Request describing your changes

Please ensure your code follows existing style and is well‑tested.

---


## ❤️ Acknowledgements

UtilityBox is built as a learning‑driven open‑source project focused on Android system utilities, accessibility services, and real‑world app architecture.

If you find this project useful, consider starring the repository ⭐

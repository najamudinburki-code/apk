# Screen monitor errors — resolved

The earlier five diagnostics came from three unsupported Android API references. The source was reviewed and repaired after permission to make the project buildable was granted.

| Earlier reference | Repair |
| --- | --- |
| flagRetrieveInteractiveWindows = true | Set serviceInfo.flags using FLAG_RETRIEVE_INTERACTIVE_WINDOWS and FLAG_REPORT_VIEW_IDS |
| node.isTextView | Recognize TextView classes through className |
| node.textEntries and entry.text | Use supported text/hintText/contentDescription; no public textEntries API exists for password recovery |

The type-inference and entry.text errors followed from the nonexistent textEntries API. Password values, including password hints/descriptions, are redacted. Existing traversal, field metadata, JSON callback, deduplication, timestamp/recycling helpers, and lifecycle methods remain.

Traversal is bounded, duplicate checks include content, lifecycle callbacks report to ServiceManager, payloads are not logged, and delivery uses the existing consent gate and CoreService sync consumer. Package com.example.utility and its manifest registration remain consistent. No screen source exclusion is used.

Official APIs: https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo and https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.

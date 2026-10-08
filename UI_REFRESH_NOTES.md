# UI refresh - 2026-10-06

- Repaired mojibake in app source strings (Loans, Wallet, offers, repayment labels, help copy). UTF-8 source is preserved.
- Settings > Appearance > Theme now offers System default (initial choice), Light and Dark, applied immediately and persisted in the appearance shared preferences.
- Theme palette keeps KashKe navy and green. Light cards use neutral slate backgrounds; dark mode uses light blue primary text/actions, green accents, dark slate cards and brighter error text for contrast.
- System-bar icon appearance follows the selected mode. Navigation, financial actions and backend data stay in their existing flows.

## Rollback

Before-edit copies: output/ui-refresh-20261006/KashKeApp.kt.before and Theme.kt.before. Text-repair originals are under output/ui-refresh-20261006/source-backup, with a list in text-repair-files.json. Restore the two before files to their original app/src/main/java locations to undo this refresh, then rebuild. These are snapshots of the immediately preceding app and include the earlier debt overview and crash fixes. Review differences before restoring if later edits exist.

Theme preference is local to this phone, defaults to SYSTEM, and contains no account or financial data. Selecting System default restores device-following behavior.

## Validation

Build passed. Scanned 123 source files with no remaining common mojibake patterns. Reviewed Home, Discover, Loans, Wallet and Profile in Light and Dark on the connected TECNO phone. Verified live mode switching, Dark persistence across reinstall/reopen, System default selection, debt dialog, and repeated Home scrolling. Crash log comparison found zero new crash entries. Screenshots and UI captures are in output/ui-refresh-20261006. Review was performed with the existing signed-in account; it does not establish every possible account state, device size, or accessibility text scale.
Additional design changes: Wallet transactions now separate the amount from a readable transaction type and short local date; the logo has a light circular backdrop for dark-mode visibility; the sign-in card uses theme surface colors.

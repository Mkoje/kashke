# Notification and connection requests update

- Notification cards and their action buttons open the linked offer, support request, repayment agreement, or connection.
- Home notification taps pass the selected notification to the same destination flow used by system notifications.
- Offer details include amount, interest, repayment period, total repayment, purpose and current status, including cancellation. Available borrower offers retain Accept and Reject.
- Repayment details include principal, interest, paid and outstanding amounts, due date, status and grace details.
- Connection requests display a red count badge only when the incoming pending count is positive.
- Connection requests reuse Discover contacts underneath incoming requests. Connected and pending contacts are excluded; unconnected members have Connect and unregistered contacts have Invite. Contact permission, matching errors and retries use the existing Discover behavior.

Validation: Android debug build and 89 existing unit tests passed (zero failures). On-phone navigation verification completed on the connected TECNO KJ5 on October 6, 2026. No financial operations were performed.
## Phone simulation

Installed output/apk/kashke.apk successfully using adb install -r. Installed update time: October 6, 2026, 10:47:49 EAT.

Verified:
- Tapping a notification card opens offer details and marks it read (unread badge decreased from 12 to 11).
- View offer on a cancelled-offer notification opens the linked offer details.
- The specific Mkoje Emmanuel updated-offer notification shows View offer and opens KSh 683 at 2% for 12 days. The current backend status is EXPIRED.
- A community-support notification opens its support request, including purpose, amount, funding and repayment period.
- A Loan repaid notification opens repayment details: KSh 655 principal, KSh 655 paid, KSh 0 outstanding, REPAID.
- Connection requests show no numeric badge at zero pending requests.
- Contacts load beneath connection requests (1,028 contacts on this account), with Invite buttons visible.

The positive-count badge was not exercised on the phone because the account has no pending requests. Connect and Invite were not submitted; no offers were accepted/rejected and no payments were made. Existing opened notifications may be marked read.

Evidence: phone-updated-offer.png, phone-repayment-details.png, phone-connection-contacts.png and associated XML dumps in output/.
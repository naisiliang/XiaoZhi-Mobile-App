# Messaging Task 2 brief

Implement a deterministic, side-effect-free SensitiveContentDetector for
ordinary WeChat/QQ text messages. It must block password/payment-password,
OTP/verification-code, recovery-code, and ambiguous credential-like content
without retaining or echoing the original text. Ordinary statements such as
“告诉张三我已经转了100元” remain allowed; this detector does not perform any
contact resolution or message send.

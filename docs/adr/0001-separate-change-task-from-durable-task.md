---
status: accepted
---

# Separate ChangeTask from DurableTask

PaiChange keeps `ChangeTask` as the durable business lifecycle and `DurableTask` as a technical queue record. They are linked by identity rather than merged because approvals, contract digests, evidence, and delivery state must survive worker retries without making queue mechanics the owner of business rules.

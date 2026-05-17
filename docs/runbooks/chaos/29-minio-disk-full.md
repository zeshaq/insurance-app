# Chaos drill 29 — MinIO storage exhausted during claim upload

Issue [#29](https://github.com/zeshaq/insurance-app/issues/29). Phase 5.

## When to run

* Before any change to `ClaimResource`, `MinioStorageService`, or the
  multipart-upload pipeline.
* Before changing the MinIO storage class or quota policy in
  `compose/`.
* Quarterly.

## What it proves

* When MinIO refuses a write (storage budget reached), `POST /api/claims`
  returns **5xx**. The drill catches the most dangerous regression:
  Liberty returning 201 with an empty `photoKey`, which would leave a
  "filed" claim that links to no attachment.
* No partial object remains in the `claims` bucket after a failed
  upload — bucket usage is operator-visible via `mc du`.
* Once the budget is restored, a re-attempt succeeds end-to-end
  (including OCR via the MI partner pipeline).

## Implementation note

The drill **does not** dd-fill the disk. The VM has hundreds of GB
free; a real fill would impact every container on the same volume.
Instead the drill uses `mc admin bucket quota --hard 1mi` to constrain
the `claims` bucket. From the application's point of view this is
indistinguishable from a full disk — MinIO returns the same XError.

The drill explicitly clears any quota in its `trap` so a failed run
doesn't leave the lab unable to file claims.

## Pre-flight

```bash
podman ps --format '{{.Names}}' | grep -E '^(insurance-app|minio)$'
podman exec minio mc alias set local http://localhost:9000 minioadmin minioadmin
podman exec minio mc ls local/claims | head -3
```

## Expected outcome

```
== drill #29: MinIO bucket quota exhaustion  (iterations=3) ==
  -- iteration 1/3 --
    setting bucket quota -> 1mi
    /api/claims under quota HTTP=500  photoKey=<none>
    PASS  iter 1: upload-over-quota returned 5xx
    PASS  iter 1: response carries NO photoKey (no partial success)
    bucket usage after failure: <some-bytes>
    clearing bucket quota
    retry HTTP=201  photoKey=<uuid>.jpg
    PASS  iter 1: retry after quota cleared -> 201
    PASS  iter 1: retry carries a photoKey
  ...
== drill #29 results: PASS=N  FAIL=0 ==
```

## Expected duration

~30-60 seconds for 3 iterations. Each iteration is a single failed
upload (~3 s, since the quota check fires fast) plus a successful
retry (~5 s including OCR via MI).

## How to run

```bash
bash /home/ze/insurance-app/tests/chaos/29-minio-disk-full.sh
```

Overrides:
* `QUOTA_SMALL=10mi` — looser quota; useful if you want to drill
  larger payloads.
* `PAYLOAD_BYTES=10485760` — 10 MiB attachment to be extra-sure the
  quota fires.

## If the assertion fails

* **`FAIL  iter N: upload-over-quota returned 5xx`** with 201 — the
  most dangerous outcome. Liberty thinks the claim was filed but
  MinIO rejected the write. Inspect `MinioStorageService.upload(...)`
  for an unchecked exception that's being swallowed before the resource
  layer can return 5xx.
* **`FAIL  iter N: response carries NO photoKey`** with a photoKey
  set — same regression as above, different symptom. The claim's
  `photoKey` field gets a value generated client-side that never
  successfully wrote.
* **`FAIL  iter N: retry after quota cleared -> 201`** — the trap
  didn't clear the quota, or MinIO is in a weird state. Run manually:
  ```bash
  podman exec minio mc quota clear local/claims
  podman exec minio mc admin bucket quota local/claims --clear
  ```

## Recovery if the drill is stuck

The trap unconditionally clears the quota. Confirm:
```bash
podman exec minio mc quota info local/claims
# Expect: "Quota is not set" or equivalent.
```

If quota is still set:
```bash
podman exec minio mc quota clear local/claims || true
podman exec minio mc admin bucket quota local/claims --clear || true
```

"""Local diagnostic script — never paste this file's output's contents back
verbatim if it happens to echo something sensitive (it's written not to).

Reads KORAIL credentials from korail_credentials.txt (same folder), which you
create yourself and never share. This script only prints success/failure and
the server's own diagnostic codes — the password itself is never printed.

Usage:
    python korail_test.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from korail_mobile_api import KorailClient, KorailConfig
from korail_mobile_api.errors import KorailApiError

CREDS_PATH = Path(__file__).parent / "korail_credentials.txt"


def load_credentials() -> tuple[str | None, str, str]:
    if not CREDS_PATH.exists():
        print(f"[!] {CREDS_PATH} 이 없습니다. LOGIN_TYPE/LOGIN_ID/PASSWORD 세 줄로 만들어주세요.")
        sys.exit(1)
    values: dict[str, str] = {}
    for line in CREDS_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip().upper()] = value.strip()

    login_type_raw = values.get("LOGIN_TYPE", "").lower()
    input_flag = {"email": "5", "phone": "4", "membership": "2"}.get(login_type_raw)
    login_id = values.get("LOGIN_ID", "")
    password = values.get("PASSWORD", "")
    if not login_id or not password:
        print("[!] LOGIN_ID 또는 PASSWORD가 비어 있습니다.")
        sys.exit(1)
    return input_flag, login_id, password


def main() -> None:
    input_flag, login_id, password = load_credentials()
    print(f"[i] 로그인 시도: id={login_id[:2]}***  (input_flag={input_flag or 'auto'})")

    config = KorailConfig(enable_dynapath=True)
    client = KorailClient(config)

    try:
        session = client.login(login_id, password, input_flag=input_flag)
    except KorailApiError as exc:
        print(f"[FAIL] {type(exc).__name__}: {exc}")
        raw = getattr(exc, "raw", None)
        if raw:
            # Print the server's raw envelope so we can see h_msg_cd / h_msg_txt,
            # but drop anything that looks like it might echo the password.
            safe_raw = {k: v for k, v in raw.items() if "pwd" not in k.lower()}
            print(f"[raw] {safe_raw}")
        sys.exit(1)
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] unexpected {type(exc).__name__}: {exc}")
        sys.exit(1)

    print("[OK] 로그인 성공!")
    print(f"     member_no={session.member_no}")
    print(f"     member_card_no={session.member_card_no}")
    print(f"     customer_no={session.customer_no}")

    # Optional: quick search smoke test. Edit these four lines to try a real query.
    dep, arr, date, time = "서울", "부산", "20260901", "090000"
    print(f"\n[i] 검색 테스트: {dep} -> {arr} {date} {time} 이후")
    try:
        from korail_mobile_api import TrainSearchQuery

        query = TrainSearchQuery(
            departure_station_code=dep,
            arrival_station_code=arr,
            departure_date=date,
            departure_time=time,
            passengers=1,
        )
        result = client.search_trains(query)
        print(f"[OK] 열차 {len(result.trains)}건 조회됨")
        for t in result.trains[:5]:
            print(
                f"     [{t.train_class_name} {t.train_no}] "
                f"{t.departure_time} -> {t.arrival_time}  "
                f"일반:{t.general_reservation_code}({t.general_availability_name}) "
                f"특실:{t.special_reservation_code}({t.special_availability_name})"
            )
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] search {type(exc).__name__}: {exc}")

    client.logout()
    client.close()


if __name__ == "__main__":
    main()

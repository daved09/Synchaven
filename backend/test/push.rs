use super::*;

/// Example from RFC 8291, section 5
#[test]
fn rfc8291_example() {
    let sender = SecretKey::from_slice(&B64.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw").unwrap()).unwrap();
    let salt: [u8; 16] = B64.decode("DGv6ra1nlYgDCS1FRnbzlw").unwrap().try_into().unwrap();
    let body = encrypt(
        b"When I grow up, I want to be a watermelon",
        "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
        "BTBZMqHH6r4Tts7J_aSIgg",
        &sender,
        &salt,
    )
    .unwrap();
    assert_eq!(
        B64.encode(body),
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
    );
}

#[test]
fn vapid_jwt_verifies() {
    use p256::ecdsa::{signature::Verifier, VerifyingKey};
    let secret = random_secret();
    let push = WebPush {
        key: SigningKey::from(&secret),
        public_key: String::new(),
        subject: "mailto:test@example.org".into(),
        client: reqwest::Client::new(),
    };
    let jwt = push.vapid_jwt("https://push.example.net");
    let (input, sig) = jwt.rsplit_once('.').unwrap();
    let sig = Signature::from_slice(&B64.decode(sig).unwrap()).unwrap();
    VerifyingKey::from(&SigningKey::from(&secret)).verify(input.as_bytes(), &sig).unwrap();
    let claims: serde_json::Value =
        serde_json::from_slice(&B64.decode(input.split('.').nth(1).unwrap()).unwrap()).unwrap();
    assert_eq!(claims["aud"], "https://push.example.net");
}

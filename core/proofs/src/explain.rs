//! The verifier's findings in plain words, for a juror or an app's error message.

use klippelgo_verify::{Error, Role};

fn who(role: Role) -> &'static str {
    match role {
        Role::Rider => "rider",
        Role::Driver => "driver",
    }
}

pub fn explain(error: &Error) -> String {
    match error {
        Error::ChunkNotInPack { chunk } => format!("map chunk {chunk} is not part of this map pack"),
        Error::MalformedChunk { chunk } => format!("map chunk {chunk} is damaged"),
        Error::WrongPack => "it was made on a different map pack".into(),
        Error::PathTooShort => "the path has fewer than two points".into(),
        Error::UnknownWay { step } => format!("step {step} of the path uses a road the map does not have"),
        Error::NotConnected { step } => format!("step {step} of the path jumps between points that are not neighbours on its road"),
        Error::NotAllowed { step } => format!("step {step} of the path goes the wrong way down a road, or on one closed to this vehicle"),
        Error::OutOfRange => "a coordinate is outside the range the verifier covers".into(),
        Error::StartTooFar { metres } => format!("the path starts {metres} m from the pickup point"),
        Error::EndTooFar { metres } => format!("the path ends {metres} m from the drop-off point"),
        Error::DistanceMismatch { claimed_m, computed_m } => {
            format!("it states {claimed_m} m but the map and logs give {computed_m} m")
        }
        Error::WrongQuote => "the receipt does not settle this quote".into(),
        Error::WrongFare => "the receipt's fare is not the quoted fare".into(),
        Error::WrongHead { role } => format!("the {}'s log is not the one the receipt names", who(*role)),
        Error::WrongTimes => "the receipt's times do not match the log".into(),
        Error::EmptyTrace => "there is no GPS log to check".into(),
        Error::FixesOutOfOrder { fix } => format!("GPS fix {fix} is not later than the one before it"),
        Error::MockLocation { fix } => format!("GPS fix {fix} came from a mock location provider"),
        Error::OffPath { fix } => format!("GPS fix {fix} is not on the driven path"),
        Error::TooFast { fix } => format!("GPS fix {fix} would need the vehicle to move faster than it can"),
        Error::ImpossibleAcceleration { fix } => format!("GPS fix {fix} changes speed faster than a vehicle can"),
        Error::BadHead { head } => format!("signed head {head} does not match the GPS log"),
        Error::HeadsTooFarApart { head } => format!("too much of the log went unsigned before head {head}"),
        Error::BadSignature { head } => format!("a signature on head {head} is not valid"),
        Error::NotCountersigned => "the other phone never countersigned the end of the log".into(),
        Error::BadCertificate => "a trace key was not certified by the right wallet for this ride".into(),
    }
}

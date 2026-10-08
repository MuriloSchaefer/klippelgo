//! BRL amounts in a stablecoin's own base units. BRLA uses 18 decimals and BRZ uses 4, so every amount
//! carries its token's decimals.

/// Flat amount paid to Instituto Klippel per completed ride, in centavos.
pub const INSTITUTE_FEE_CENTAVOS: u128 = 100;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Brl {
    pub base_units: u128,
    pub decimals: u8,
}

impl Brl {
    /// Builds an amount from centavos, e.g. `Brl::from_centavos(2340, 4)` is R$ 23,40 in BRZ.
    pub fn from_centavos(centavos: u128, decimals: u8) -> Self {
        assert!(decimals >= 2, "BRL tokens have at least 2 decimals");
        Brl {
            base_units: centavos * 10u128.pow(decimals as u32 - 2),
            decimals,
        }
    }

    pub fn centavos(&self) -> u128 {
        self.base_units / 10u128.pow(self.decimals as u32 - 2)
    }

    /// Formats as Brazilian reais, e.g. `R$ 1.234,50`.
    pub fn format_pt_br(&self) -> String {
        let c = self.centavos();
        let reais = (c / 100).to_string();
        let mut grouped = String::new();
        for (i, ch) in reais.chars().enumerate() {
            if i > 0 && (reais.len() - i).is_multiple_of(3) {
                grouped.push('.');
            }
            grouped.push(ch);
        }
        format!("R$ {},{:02}", grouped, c % 100)
    }
}

/// What the driver receives for a fare: the fare minus R$ 1,00 for Instituto Klippel. Matches
/// `RideEscrow._settle`. Returns `None` when the fare does not cover the fee (the contract rejects it).
pub fn driver_payout(fare: Brl) -> Option<Brl> {
    let fee = Brl::from_centavos(INSTITUTE_FEE_CENTAVOS, fare.decimals);
    (fare.base_units > fee.base_units).then(|| Brl {
        base_units: fare.base_units - fee.base_units,
        decimals: fare.decimals,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn payout_deducts_one_real_in_any_decimals() {
        assert_eq!(
            driver_payout(Brl::from_centavos(2340, 4))
                .unwrap()
                .base_units,
            22_4000
        );
        assert_eq!(
            driver_payout(Brl::from_centavos(2340, 18))
                .unwrap()
                .centavos(),
            2240
        );
        assert_eq!(driver_payout(Brl::from_centavos(100, 4)), None);
    }

    #[test]
    fn formats_like_brazil() {
        assert_eq!(Brl::from_centavos(2340, 4).format_pt_br(), "R$ 23,40");
        assert_eq!(Brl::from_centavos(123450, 18).format_pt_br(), "R$ 1.234,50");
    }
}

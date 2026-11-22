// Site-wide settings for Roadside.
//
// mapillaryToken: a Mapillary *client token* (starts with "MLY|").
//   Get one free at https://www.mapillary.com/dashboard/developers
//   Client tokens are meant to be used in browser code, so it is fine to commit it,
//   but anyone can copy it from your site. If it gets abused, rotate it on the
//   Mapillary dashboard. Players can also paste their own token in the game's settings.
//
// Leave it empty ("") and each player is asked for their own token.
window.ROADSIDE_CONFIG = {
  mapillaryToken: "",
  roundsPerGame: 5,
  // OpenStreetMap tiles for the guess map. Swap for another provider if your traffic grows.
  tileUrl: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
  tileAttribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
};

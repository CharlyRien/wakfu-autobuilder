<a name="readme-top"></a>

<!-- PROJECT SHIELDS -->
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]


<!-- PROJECT LOGO -->
<br />
<div align="center">
  <a href="https://github.com/CharlyRien/wakfu-autobuilder">
    <img src="gui-compose/src/main/resources/assets/branding/wordmark.png" alt="Wakfu Auto-Builder" width="480">
  </a>

<h3 align="center">Wakfu autobuilder</h3>

  <p align="center">
    <br />
    ·
    <a href="https://github.com/CharlyRien/wakfu-autobuilder/issues">Report Bug</a>
    ·
    <a href="https://github.com/CharlyRien/wakfu-autobuilder/issues">Request Feature</a>
  </p>
</div>



<!-- TABLE OF CONTENTS -->
<details>
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#built-with">Built With</a></li>
      </ul>
    </li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>



<!-- ABOUT THE PROJECT -->

## About The Project

The Wakfu Autobuilder is a toolkit, consisting of:

* A Command-Line Interface (CLI)
* A desktop Graphical User Interface (GUI), built with Compose Desktop

These tools identify the best equipment setup for your character at a given level: you provide
constraints (level, class, target characteristics, max rarity, forced/excluded items) and the
**search engine** explores the item space to return the best build it found (14 equipment slots +
skill-point allocation), which it can publish as a shareable [zenithwakfu.com](https://zenithwakfu.com) link.

The engine uses a **Google OR-Tools CP-SAT solver** by default, which finds a *provably optimal*
build (a genetic-algorithm solver remains available as an alternative).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Built With

* [![Kotlin][Kotlin]][Kotlin-url]
* [![Compose][Compose]][Compose-url]
* [![OR-Tools][ORTools]][ORTools-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- USAGE EXAMPLES -->

## Usage GUI (User Interface)

To use Wakfu Autobuilder, you need to download the executable file for your operating system from the [releases](https://github.com/CharlyRien/wakfu-autobuilder/releases) page and
execute it

> [!IMPORTANT]  
> I'm not paying any developer license (because it's too expensive).
> Therefore it's possible that in some OS you will have to bypass security for being able to start the executable for the first time

## Usage CLI (Command Line Interface)

If you don't want to use the user interface you can still use it via the CLI.

For that you need first some [prerequisites](#prerequisites) and [installation](#installation)

After that the documentation of the usage can be found using the `--help` command, that will list the available options and their descriptions.

```sh
./gradlew :autobuilder:run --args="--help"
```

Here is an example of how to use Wakfu Autobuilder in your terminal

```sh
./gradlew :autobuilder:run --args="--level 110 --action-point 11 --movement-point 5 --mastery-distance 500 --hp 2000 --range 2 --cc 30 --class cra --create-zenith-build --duration 60"
```

In this example, this command will search for the best equipment combination for a level 110 Cra with 11 action points, 5 movement points, 500 distance mastery, 2000 hp, 2 range,
and 30 critical hit.

It will also create a zenith build.

The search will last for 60 seconds.

The output of the command will show the details of the best build found, such as the equipment names and a link to reach your zenith wakfu build created.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- ROADMAP -->

## Roadmap

- [X] Have a User Interface (Compose Desktop)
- [X] Provably optimal builds (OR-Tools CP-SAT solver)
- [X] Multi-language Support (English / French)
- [ ] Enchantments support (runes + sublimations) — see `docs/ENCHANTMENTS_PLAN.md`
- [ ] Add Changelog

See the [open issues](https://github.com/CharlyRien/wakfu-autobuilder/issues) for a full list of proposed features (and known issues).

<p align="right">(<a href="#readme-top">back to top</a>)</p>


<!-- CONTRIBUTING -->

## Contributing

Contributions are what make the open source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

If you have a suggestion that would make this better, please create a pull request. You can also open an issue with the tag "enhancement".
Remember to give the project a star! Thanks again!

1. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
2. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
3. Push to the Branch (`git push origin feature/AmazingFeature`)
4. Open a Pull Request

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- GETTING STARTED -->

## Getting Started For Developer Contributors

### Prerequisites

For this project you'll need to install:

* [Java 25](https://adoptium.net/) (the Gradle toolchain is pinned to JDK 25)

### Installation

1. Clone the repo
   ```sh
   git clone https://github.com/CharlyRien/wakfu-autobuilder.git
   ```
2. Build the project with the Gradle wrapper included in the repository
   ```sh
   ./gradlew build
   ```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Test

#### Test GUI

To test your release for the GUI you can use the command:

```sh
./gradlew :gui-compose:run
```

It will compile and start the Compose Desktop GUI with your local changes.

#### Test CLI

To test your release for the CLI you can use the command:

```sh
./gradlew :autobuilder:run --args="--help"
```

This command will package everything into a single executable file for the following platforms (x64):

* Linux
* Windows
* macOS

After that you can use your executables created like any user would do.

<!-- LICENSE -->

## License

<!-- CONTACT -->

## Contact

Project Link: [https://github.com/CharlyRien/wakfu-autobuilder](https://github.com/CharlyRien/wakfu-autobuilder)
Discord: Chosante

<p align="right">(<a href="#readme-top">back to top</a>)</p>



<!-- ACKNOWLEDGMENTS -->

## Acknowledgments

* [Choose an Open Source License](https://choosealicense.com)

<p align="right">(<a href="#readme-top">back to top</a>)</p>



<!-- MARKDOWN LINKS & IMAGES -->
<!-- https://www.markdownguide.org/basic-syntax/#reference-style-links -->

[forks-shield]: https://img.shields.io/github/forks/CharlyRien/wakfu-autobuilder.svg?style=for-the-badge

[forks-url]: https://github.com/CharlyRien/wakfu-autobuilder/network/members

[stars-shield]: https://img.shields.io/github/stars/CharlyRien/wakfu-autobuilder.svg?style=for-the-badge

[stars-url]: https://github.com/CharlyRien/wakfu-autobuilder/stargazers

[issues-shield]: https://img.shields.io/github/issues/CharlyRien/wakfu-autobuilder.svg?style=for-the-badge

[issues-url]: https://github.com/CharlyRien/wakfu-autobuilder/issues

[Kotlin]: https://img.shields.io/badge/kotlin-blue?logo=kotlin&style=for-the-badge

[Kotlin-url]: https://kotlinlang.org/

[Compose]: https://img.shields.io/badge/Compose%20Desktop-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white

[Compose-url]: https://www.jetbrains.com/lp/compose-multiplatform/

[ORTools]: https://img.shields.io/badge/OR--Tools%20CP--SAT-EA4335?style=for-the-badge&logo=google&logoColor=white

[ORTools-url]: https://developers.google.com/optimization

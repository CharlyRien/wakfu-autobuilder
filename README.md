<a name="readme-top"></a>

<!-- PROJECT SHIELDS -->
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]


<!-- PROJECT LOGO -->
<br />
<div align="center">
  <a href="https://github.com/CharlyRien/wakfu-autobuilder">
    <img src="gui/src/main/resources/logo.png" alt="Logo" width="512" height="512">
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

Wakfu Autobuilder is a command-line interface (CLI) tool that try to find the optimal equipment combination for your character at a given level by taking into account your desired
stats based on your input.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Built With

* [![Kotlin][Kotlin]][Kotlin-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- USAGE EXAMPLES -->

## Usage

To use Wakfu Autobuilder, you need to download the executable file for your operating system from the [releases](https://github.com/CharlyRien/wakfu-autobuilder/releases) page and
run it in your terminal with the appropriate options.

You can also use the --help option to see the available options and their descriptions.

For example on Windows

```sh
./wakfu-autobuilder-cli.exe --help
```

Here is an example of how to use Wakfu Autobuilder in your terminal (for Windows on this example):

```sh
./wakfu-autobuilder-cli.exe --level 110 --action-point 11 --movement-point 5 --mastery-distance 500 --hp 2000 --range 2 --cc 30 --class cra --create-zenith-build --duration 60
```

In this example, this command will search for the best equipment combination for a level 110 Cra with 11 action points, 5 movement points, 500 distance mastery, 2000 hp, 2 range,
and 30 critical hit.

It will also create a zenith build.

The search will last for 60 seconds.

The output of the command will show the details of the best build found, such as the equipment names and a link to reach your zenith wakfu build created.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- ROADMAP -->

## Roadmap

- [ ] Add Changelog
- [ ] Have a User Interface
- [ ] Multi-language Support
    - [ ] French

See the [open issues](https://github.com/CharlyRien/wakfu-autobuilder/issues) for a full list of proposed features (and known issues).

<p align="right">(<a href="#readme-top">back to top</a>)</p>


<!-- CONTRIBUTING -->

## Contributing

Contributions are what make the open source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

If you have a suggestion that would make this better, please create a pull request. You can also simply open an issue with the tag "enhancement".
Don't forget to give the project a star! Thanks again!

1. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
2. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
3. Push to the Branch (`git push origin feature/AmazingFeature`)
4. Open a Pull Request

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- GETTING STARTED -->

## Getting Started For Developer Contributors

### Prerequisites

For this project you'll need to install:

* [Java 21](https://www.oracle.com/fr/java/technologies/downloads/)

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

To test your release you can use the command:

```sh
./gradlew make 
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

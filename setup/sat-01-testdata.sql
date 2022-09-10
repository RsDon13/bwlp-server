-- phpMyAdmin SQL Dump
-- version 5.1.0
-- https://www.phpmyadmin.net/
--
-- Host: 192.168.200.20
-- Generation Time: May 20, 2021 at 09:56 AM
-- Server version: 10.5.10-MariaDB-1:10.5.10+maria~focal
-- PHP Version: 7.4.19

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- Database: `sat`
--
CREATE DATABASE IF NOT EXISTS `sat` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `sat`;

--
-- Dumping data for table `operatingsystem`
--

INSERT INTO `operatingsystem` (`osid`, `displayname`, `architecture`, `maxmem`, `maxcpu`) VALUES
(1, 'Windows 7 (64 Bit)', 'AMD64', 196608, 256),
(2, 'Windows 8 (32 Bit)', 'x86', 4096, 32),
(3, 'Windows 8 (64 Bit)', 'AMD64', 131072, 256),
(4, 'Ubuntu (32 Bit)', 'x86', 0, 0),
(5, 'Ubuntu (64 Bit)', 'AMD64', 0, 0),
(6, 'OpenSUSE (32 Bit)', 'x86', 0, 0),
(7, 'OpenSUSE (64 Bit)', 'AMD64', 0, 0),
(8, 'Other Linux (32 Bit)', 'x86', 0, 0),
(9, 'Other Linux (64 Bit)', 'AMD64', 0, 0),
(10, 'Windows 7 (32 Bit)', 'x86', 4096, 32),
(11, 'Windows 2000 Professional', 'x86', 4096, 4),
(12, 'Windows XP (32 Bit)', 'x86', 4096, 8),
(13, 'Debian (32Bit)', 'x86', 0, 0),
(14, 'Debian (64Bit)', 'AMD64', 0, 0),
(15, 'DOS', 'x86', 32, 1),
(16, 'Anderes (32 Bit)', 'x86', 0, 0),
(17, 'Anderes (64 Bit)', 'AMD64', 0, 0),
(18, 'Windows 10 (64 Bit)', 'AMD64', 131072, 256),
(19, 'Windows NT 4', 'x86', 4096, 4);

--
-- Dumping data for table `organization`
--

INSERT INTO `organization` (`organizationid`, `displayname`, `canlogin`) VALUES
('blblogin.blb-karlsruhe.de', 'Badische Landesbibliothek', 0),
('bsz-bw.de', 'Bibliotheksservice-Zentrum Baden-Württemberg', 0),
('cas.dhbw.de', 'DHBW CAS', 0),
('dhbw-heidenheim.de', 'DHBW Heidenheim', 0),
('dhbw-karlsruhe.de', 'DHBW Karlsruhe', 0),
('dhbw-loerrach.de', 'DHBW Lörrach', 0),
('dhbw-mannheim.de', 'DHBW Mannheim', 0),
('dhbw-ravensburg.de', 'DHBW Ravensburg', 0),
('dhbw-vs.de', 'DHBW Villingen-Schwenningen', 0),
('eh-freiburg.ekiba.de', 'Evangelische Hochschule Freiburg', 0),
('hdm-stuttgart.de', 'Hochschule der Medien Stuttgart', 0),
('hft-stuttgart.de', 'Hochschule für Technik Stuttgart', 0),
('hfwu.de', 'HfWU Nürtingen-Geislingen', 0),
('hochschule-bc.de', 'Hochschule Biberach', 0),
('hs-albsig.de', 'Hochschule Albstadt-Sigmaringen', 0),
('hs-esslingen.de', 'Hochschule Esslingen', 0),
('hs-furtwangen.de', 'Hochschule Furtwangen', 0),
('hs-heilbronn.de', 'Hochschule Heilbronn', 0),
('hs-karlsruhe.de', 'Hochschule Karlsruhe - Technik und Wirtschaft', 0),
('hs-kehl.de', 'Hochschule für öffentliche Verwaltung Kehl', 0),
('hs-ludwigsburg.de', 'Hochschule für öffentliche Verwaltung und Finanzen Ludwigsburg', 0),
('hs-mannheim.de', 'Hochschule Mannheim', 0),
('hs-offenburg.de', 'Hochschule Offenburg', 0),
('hs-pforzheim.de', 'Hochschule Pforzheim IdP', 0),
('hs-rottenburg.de', 'Hochschule für Forstwirtschaft Rottenburg (HFR)', 0),
('hs-ulm.de', 'Technische Hochschule Ulm', 0),
('hs-weingarten.de', 'Hochschule Ravensburg-Weingarten', 0),
('htw-aalen.de', 'Hochschule Aalen - Technik und Wirtschaft', 0),
('htwg-konstanz.de', 'HTWG Konstanz', 0),
('kit.edu', 'Karlsruher Institut für Technologie (KIT)', 0),
('lb.ph-ludwigsburg.de', 'PH Ludwigsburg', 0),
('lehre.dhbw-stuttgart.de', 'DHBW Stuttgart', 0),
('mh-freiburg.de', 'Hochschule für Musik Freiburg', 0),
('ph-freiburg.de', 'PH Freiburg', 0),
('ph-gmuend.de', 'PH Schwäbisch Gmünd', 0),
('ph-heidelberg.de', 'Pädagogische Hochschule Heidelberg', 0),
('ph-karlsruhe.de', 'PH Karlsruhe', 0),
('ph-weingarten.de', 'PH Weingarten', 0),
('praesidium.dhbw.de', 'DHBW', 0),
('reutlingen-university.de', 'Reutlingen University / Hochschule Reutlingen', 0),
('uni-freiburg.de', 'Albert-Ludwigs-Universität Freiburg', 0),
('uni-heidelberg.de', 'Universität Heidelberg', 0),
('uni-hohenheim.de', 'Universität Hohenheim', 0),
('uni-konstanz.de', 'Universität Konstanz', 0),
('uni-mannheim.de', 'Universität Mannheim', 0),
('uni-stuttgart.de', 'Universität Stuttgart', 0),
('uni-tuebingen.de', 'Universität Tübingen', 0),
('uni-ulm.de', 'Universität Ulm', 0),
('unibas.ch', 'Uni Basel', 0);

--
-- Dumping data for table `virtualizer`
--

INSERT INTO `virtualizer` (`virtid`, `virtname`) VALUES
('docker', 'Docker'),
('qemukvm', 'QEMU-KVM'),
('virtualbox', 'VirtualBox'),
('vmware', 'VMware');

--
-- Dumping data for table `os_x_virt`
--

INSERT INTO `os_x_virt` (`osid`, `virtid`, `virtoskeyword`) VALUES
(1, 'virtualbox', 'Windows7_64'),
(1, 'vmware', 'windows7-64'),
(2, 'virtualbox', 'Windows8'),
(2, 'vmware', 'windows8'),
(3, 'virtualbox', 'Windows8_64'),
(3, 'vmware', 'windows8-64'),
(4, 'virtualbox', 'Ubuntu'),
(4, 'vmware', 'ubuntu'),
(5, 'virtualbox', 'Ubuntu_64'),
(5, 'vmware', 'ubuntu-64'),
(6, 'virtualbox', 'OpenSUSE'),
(6, 'vmware', 'opensuse'),
(7, 'virtualbox', 'OpenSUSE_64'),
(7, 'vmware', 'opensuse-64'),
(8, 'virtualbox', 'Linux'),
(8, 'vmware', 'other3xlinux'),
(9, 'virtualbox', 'Linux_64'),
(9, 'vmware', 'other3xlinux-64'),
(10, 'virtualbox', 'Windows7'),
(10, 'vmware', 'windows7'),
(11, 'virtualbox', 'Windows2000'),
(11, 'vmware', 'win2000pro'),
(12, 'virtualbox', 'WindowsXP'),
(12, 'vmware', 'winxppro'),
(13, 'virtualbox', 'Debian'),
(13, 'vmware', 'debian8'),
(14, 'virtualbox', 'Debian_64'),
(14, 'vmware', 'debian8-64'),
(15, 'virtualbox', 'DOS'),
(15, 'vmware', 'dos'),
(16, 'virtualbox', 'Other'),
(16, 'vmware', 'other'),
(17, 'virtualbox', 'Other_64'),
(17, 'vmware', 'other-64'),
(18, 'virtualbox', 'Windows10_64'),
(18, 'vmware', 'windows9-64'),
(19, 'virtualbox', 'WindowsNT'),
(19, 'vmware', 'winnt');
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
